package nhcm.jvmrtdp.handles.jvm;

import nhcm.jvmrtdp.command.CommandLine;
import nhcm.jvmrtdp.handles.RemoteHandle;
import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.handles.java.RemoteClass;
import nhcm.jvmrtdp.handles.java.RemoteConstructor;
import nhcm.jvmrtdp.handles.java.RemoteClassInfo;
import nhcm.jvmrtdp.handles.java.RemoteField;
import nhcm.jvmrtdp.handles.java.RemoteMethod;
import nhcm.jvmrtdp.handles.java.RemoteMapEntry;
import nhcm.jvmrtdp.handles.java.RemoteObject;
import nhcm.jvmrtdp.handles.java.RemoteObjectDebugInfo;
import nhcm.jvmrtdp.handles.java.RemotePackage;
import nhcm.jvmrtdp.handles.search.RemoteClassQuery;
import nhcm.jvmrtdp.handles.search.RemoteMemberQuery;
import nhcm.jvmrtdp.protocol.RemoteObjectDescriptor;
import nhcm.jvmrtdp.protocol.TextWireCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/** Controller-side JNI object model. */
public class RemoteJNIEnv extends RemoteHandle {
    public RemoteJNIEnv(ServerHandle server, long remoteId) {
        super(server, remoteId);
    }

    public RemoteClass findClass(String className) {
        return new RemoteClass(server(), allocateRemoteId(), className, this, server().javaVM().jvmtiEnv());
    }

    /** Executes Class.forName with initialization in the target JVM and returns its class handle. */
    public RemoteClass forceLoadClass(String className) {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("Class name must not be empty");
        }
        String loadedName = executeForOutput(CommandLine.of("object", "class.load", className.trim()));
        return findClass(loadedName);
    }

    /** Starts initialization on a debugger-visible target thread and returns after linking. */
    public RemoteClass startForceLoadClass(String className) {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("Class name must not be empty");
        }
        String loadedName = executeForOutput(CommandLine.of(
                "object", "class.load.start", className.trim()));
        return findClass(loadedName);
    }

    public RemotePackage findPackage(String packageName) {
        String output = executeForOutput(CommandLine.of("object", "package", packageName));
        List<String> packages = new ArrayList<String>();
        List<String> classes = new ArrayList<String>();
        if (!output.isEmpty()) {
            for (String row : output.split("\\r?\\n")) {
                List<String> fields = TextWireCodec.decode(row, 2);
                if ("package".equals(fields.get(0))) packages.add(fields.get(1));
                else if ("class".equals(fields.get(0))) classes.add(fields.get(1));
            }
        }
        return new RemotePackage(packageName, packages, classes);
    }

    /** Complete loaded-class name snapshot, including classes beyond search display limits. */
    public List<String> loadedClassNames() {
        String output = executeForOutput(CommandLine.of("object", "class.names"));
        if (output.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<String>();
        Collections.addAll(result, output.split("\\r?\\n"));
        return Collections.unmodifiableList(result);
    }

    /** Reads {@link System#getProperty(String)} inside the target JVM. */
    public String systemProperty(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        return executeForOutput(CommandLine.of("object", "system.property", name));
    }

    public List<RemoteClassInfo> searchClasses(RemoteClassQuery query) {
        if (query == null) throw new IllegalArgumentException("query must not be null");
        String output = executeForOutput(CommandLine.of(
                "object", "class.search", query.nameGlob(), query.kind(), query.packageGlob(),
                query.extendsGlob(), query.implementsGlob(), Integer.toString(query.limit())));
        if (output.isEmpty()) return Collections.emptyList();
        List<RemoteClassInfo> result = new ArrayList<RemoteClassInfo>();
        for (String row : output.split("\\r?\\n")) result.add(decodeClassInfo(row));
        return Collections.unmodifiableList(result);
    }

    public List<String> searchPackages(String glob, int limit) {
        if (limit < 1 || limit > 10000) {
            throw new IllegalArgumentException("limit must be between 1 and 10000");
        }
        String output = executeForOutput(CommandLine.of(
                "object", "package.search", glob == null || glob.isEmpty() ? "*" : glob,
                Integer.toString(limit)));
        if (output.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<String>();
        for (String row : output.split("\\r?\\n")) result.add(TextWireCodec.decode(row, 1).get(0));
        return Collections.unmodifiableList(result);
    }

    public List<RemoteField> searchFields(RemoteMemberQuery query) {
        if (query == null) throw new IllegalArgumentException("query must not be null");
        String output = executeForOutput(CommandLine.of(
                "object", "field.search", query.classGlob(), query.nameGlob(), query.typeGlob(),
                query.mode(), Integer.toString(query.limit())));
        if (output.isEmpty()) return Collections.emptyList();
        List<RemoteField> result = new ArrayList<RemoteField>();
        for (String row : output.split("\\r?\\n")) {
            List<String> fields = TextWireCodec.decode(row, 5);
            RemoteClass owner = findClass(fields.get(0));
            result.add(new RemoteField(server(), allocateRemoteId(), owner,
                    fields.get(0), fields.get(1), fields.get(2), Integer.parseInt(fields.get(3))));
        }
        return Collections.unmodifiableList(result);
    }

    public List<RemoteMethod> searchMethods(RemoteMemberQuery query) {
        if (query == null) throw new IllegalArgumentException("query must not be null");
        String output = executeForOutput(CommandLine.of(
                "object", "method.search", query.classGlob(), query.nameGlob(), query.typeGlob(),
                query.parametersGlob(), query.mode(), Integer.toString(query.limit())));
        if (output.isEmpty()) return Collections.emptyList();
        List<RemoteMethod> result = new ArrayList<RemoteMethod>();
        for (String row : output.split("\\r?\\n")) {
            List<String> fields = TextWireCodec.decode(row, 5);
            RemoteClass owner = findClass(fields.get(0));
            result.add(new RemoteMethod(server(), allocateRemoteId(), owner,
                    fields.get(0), fields.get(1), fields.get(2), Integer.parseInt(fields.get(3))));
        }
        return Collections.unmodifiableList(result);
    }

    public RemoteObject valueOf(Object value) {
        String kind;
        String text;
        if (value == null) {
            kind = "null";
            text = "";
        } else if (value instanceof String) {
            kind = "string";
            text = (String) value;
        } else if (value instanceof Boolean) {
            kind = "boolean";
            text = value.toString();
        } else if (value instanceof Byte) {
            kind = "byte";
            text = value.toString();
        } else if (value instanceof Short) {
            kind = "short";
            text = value.toString();
        } else if (value instanceof Integer) {
            kind = "int";
            text = value.toString();
        } else if (value instanceof Long) {
            kind = "long";
            text = value.toString();
        } else if (value instanceof Float) {
            kind = "float";
            text = value.toString();
        } else if (value instanceof Double) {
            kind = "double";
            text = value.toString();
        } else if (value instanceof Character) {
            kind = "char";
            text = value.toString();
        } else if (value instanceof byte[]) {
            kind = "bytes";
            text = Base64.getEncoder().encodeToString((byte[]) value);
        } else if (value instanceof Enum<?>) {
            kind = "enum:" + value.getClass().getName();
            text = ((Enum<?>) value).name();
        } else {
            throw new IllegalArgumentException("Unsupported local value type: " + value.getClass().getName());
        }
        return remoteValue(kind, text);
    }

    /** Creates the target JVM's java.lang.Class object for a loaded class name. */
    public RemoteObject classValue(String className) {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("Class literal name must not be empty");
        }
        return remoteValue("class", className);
    }

    /** Creates a target enum constant without loading the enum type in the controller JVM. */
    public RemoteObject enumValue(String className, String constant) {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("Enum class name must not be empty");
        }
        if (constant == null || constant.trim().isEmpty()) {
            throw new IllegalArgumentException("Enum constant must not be empty");
        }
        return remoteValue("enum:" + className, constant);
    }

    private RemoteObject remoteValue(String kind, String text) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
        return object(executeForOutput(CommandLine.of("object", "value", kind, encoded)));
    }

    public List<RemoteConstructor> listConstructors(RemoteClass owner) {
        String output = executeForOutput(CommandLine.of("object", "constructors", owner.className()));
        if (output.isEmpty()) return Collections.emptyList();
        List<RemoteConstructor> result = new ArrayList<RemoteConstructor>();
        for (String row : output.split("\\r?\\n")) {
            List<String> fields = TextWireCodec.decode(row, 3);
            result.add(new RemoteConstructor(
                    server(), allocateRemoteId(), owner, fields.get(1), Integer.parseInt(fields.get(2))));
        }
        return Collections.unmodifiableList(result);
    }

    public RemoteClassInfo classInfo(RemoteClass owner) {
        return decodeClassInfo(executeForOutput(CommandLine.of(
                "object", "class.info", owner.className())));
    }

    private static RemoteClassInfo decodeClassInfo(String encoded) {
        List<String> fields = TextWireCodec.decode(encoded, 7);
        List<String> interfaces = fields.get(3).isEmpty()
                ? Collections.<String>emptyList()
                : java.util.Arrays.asList(fields.get(3).split(","));
        return new RemoteClassInfo(
                fields.get(0), Integer.parseInt(fields.get(1)), fields.get(2), interfaces,
                Boolean.parseBoolean(fields.get(4)), Boolean.parseBoolean(fields.get(5)),
                Boolean.parseBoolean(fields.get(6)));
    }

    public List<RemoteMethod> listMethods(RemoteClass owner, boolean staticMethods) {
        String output = executeForOutput(CommandLine.of(
                "object", "methods", owner.className(), staticMethods ? "static" : "virtual"));
        if (output.isEmpty()) return Collections.emptyList();
        List<RemoteMethod> result = new ArrayList<RemoteMethod>();
        for (String row : output.split("\\r?\\n")) {
            List<String> fields = TextWireCodec.decode(row, 5);
            result.add(new RemoteMethod(
                    server(), allocateRemoteId(), owner,
                    fields.get(0), fields.get(1), fields.get(2), Integer.parseInt(fields.get(3))));
        }
        return Collections.unmodifiableList(result);
    }

    public List<RemoteField> listFields(RemoteClass owner, boolean staticFields) {
        String output = executeForOutput(CommandLine.of(
                "object", "fields", owner.className(), staticFields ? "static" : "virtual"));
        if (output.isEmpty()) return Collections.emptyList();
        List<RemoteField> result = new ArrayList<RemoteField>();
        for (String row : output.split("\\r?\\n")) {
            List<String> fields = TextWireCodec.decode(row, 5);
            result.add(new RemoteField(
                    server(), allocateRemoteId(), owner,
                    fields.get(0), fields.get(1), fields.get(2), Integer.parseInt(fields.get(3))));
        }
        return Collections.unmodifiableList(result);
    }

    public RemoteObject construct(RemoteClass type, String descriptor, RemoteObject... arguments) {
        List<String> command = objectCommand("construct", type.className(), descriptor);
        addIds(command, arguments);
        return object(executeForOutput(CommandLine.of("object", command.toArray(new String[0]))));
    }

    public RemoteObject call(RemoteMethod method, RemoteObject receiver, RemoteObject... arguments) {
        List<String> command = objectCommand(
                "call", method.declaringClass(), method.name(), method.descriptor(), id(receiver));
        addIds(command, arguments);
        return object(executeForOutput(CommandLine.of("object", command.toArray(new String[0]))));
    }

    public RemoteObject callSpecial(RemoteMethod method, RemoteObject receiver, RemoteObject... arguments) {
        requireObject(receiver);
        List<String> command = objectCommand(
                "call.special", method.declaringClass(), method.name(), method.descriptor(), id(receiver));
        addIds(command, arguments);
        return object(executeForOutput(CommandLine.of("object", command.toArray(new String[0]))));
    }

    public boolean isInstance(RemoteClass type, RemoteObject object) {
        requireObject(object);
        return Boolean.parseBoolean(executeForOutput(CommandLine.of(
                "object", "instanceof", type.className(), Long.toString(object.remoteId()))));
    }

    public RemoteObject readField(RemoteField field, RemoteObject receiver) {
        return object(executeForOutput(CommandLine.of(
                "object", "field.get", field.declaringClass(), field.name(), field.descriptor(), id(receiver))));
    }

    public void writeField(RemoteField field, RemoteObject receiver, RemoteObject value) {
        requireObject(value);
        executeForOutput(CommandLine.of(
                "object", "field.set", field.declaringClass(), field.name(), field.descriptor(),
                id(receiver), Long.toString(value.remoteId())));
    }

    public <T> T materialize(RemoteObject object, Class<T> clazz) {
        if (clazz == null) throw new IllegalArgumentException("clazz must not be null");
        List<String> fields = TextWireCodec.decode(executeForOutput(CommandLine.of(
                "object", "as", Long.toString(object.remoteId()))), 3);
        String kind = fields.get(0);
        String value = fields.get(1);
        if ("null".equals(kind)) {
            if (clazz.isPrimitive()) throw new IllegalArgumentException("Cannot convert null to " + clazz.getName());
            return null;
        }
        Object converted;
        if (clazz == String.class || "string".equals(kind) || "display".equals(kind)) converted = value;
        else if ("boolean".equals(kind)) converted = Boolean.valueOf(value);
        else if ("byte".equals(kind)) converted = Byte.valueOf(value);
        else if ("short".equals(kind)) converted = Short.valueOf(value);
        else if ("int".equals(kind)) converted = Integer.valueOf(value);
        else if ("long".equals(kind)) converted = Long.valueOf(value);
        else if ("float".equals(kind)) converted = Float.valueOf(value);
        else if ("double".equals(kind)) converted = Double.valueOf(value);
        else if ("char".equals(kind)) converted = Character.valueOf(value.charAt(0));
        else if ("bytes".equals(kind)) converted = Base64.getDecoder().decode(value);
        else if ("enum".equals(kind) && clazz.isEnum()) converted = enumValue(clazz, value);
        else throw new IllegalArgumentException("Cannot convert remote " + fields.get(2) + " to " + clazz.getName());
        if (!clazz.isPrimitive() && clazz != Object.class && !clazz.isInstance(converted)) {
            throw new IllegalArgumentException("Remote value is not a " + clazz.getName());
        }
        @SuppressWarnings("unchecked")
        T result = (T) converted;
        return result;
    }

    public void release(RemoteObject object) {
        executeForOutput(CommandLine.of("object", "release", Long.toString(object.remoteId())));
    }

    public void refresh(RemoteObject object) {
        requireObject(object);
        RemoteObjectDescriptor descriptor = RemoteObjectDescriptor.decode(executeForOutput(CommandLine.of(
                "object", "describe", Long.toString(object.remoteId()))));
        if (descriptor.id() != object.remoteId()) {
            throw new IllegalStateException("Target returned a different object ID while refreshing context");
        }
        object.updateDescriptor(descriptor.className(), descriptor.nullValue(), descriptor.displayValue());
    }

    public void releaseAll(RemoteObject... objects) {
        List<String> command = objectCommand("release");
        addIds(command, objects);
        if (command.size() > 1) executeForOutput(CommandLine.of("object", command.toArray(new String[0])));
    }

    public int arrayLength(RemoteObject array) {
        requireObject(array);
        return Integer.parseInt(executeForOutput(CommandLine.of(
                "object", "array.length", Long.toString(array.remoteId()))));
    }

    public RemoteObject arrayGet(RemoteObject array, int index) {
        requireObject(array);
        return object(executeForOutput(CommandLine.of(
                "object", "array.get", Long.toString(array.remoteId()), Integer.toString(index))));
    }

    public void arraySet(RemoteObject array, int index, RemoteObject value) {
        requireObject(array);
        requireObject(value);
        executeForOutput(CommandLine.of("object", "array.set", Long.toString(array.remoteId()),
                Integer.toString(index), Long.toString(value.remoteId())));
    }

    public List<RemoteObject> iterableElements(RemoteObject iterable, int limit) {
        requireObject(iterable);
        String output = executeForOutput(CommandLine.of(
                "object", "iterable", Long.toString(iterable.remoteId()), Integer.toString(limit)));
        if (output.isEmpty()) return Collections.emptyList();
        List<RemoteObject> result = new ArrayList<RemoteObject>();
        for (String row : output.split("\\r?\\n")) result.add(object(row));
        return result;
    }

    public List<RemoteMapEntry> mapEntries(RemoteObject map, int limit) {
        requireObject(map);
        String output = executeForOutput(CommandLine.of(
                "object", "map", Long.toString(map.remoteId()), Integer.toString(limit)));
        if (output.isEmpty()) return Collections.emptyList();
        List<RemoteMapEntry> result = new ArrayList<RemoteMapEntry>();
        for (String row : output.split("\\r?\\n")) {
            List<String> fields = TextWireCodec.decode(row, 2);
            result.add(new RemoteMapEntry(object(fields.get(0)), object(fields.get(1))));
        }
        return result;
    }

    public RemoteRuntimeStats statistics() {
        List<String> fields = TextWireCodec.decode(
                executeForOutput(CommandLine.of("object", "stats")), 8);
        return new RemoteRuntimeStats(
                Integer.parseInt(fields.get(0)), Long.parseLong(fields.get(1)),
                Integer.parseInt(fields.get(2)), Integer.parseInt(fields.get(3)),
                Long.parseLong(fields.get(4)), Long.parseLong(fields.get(5)),
                Long.parseLong(fields.get(6)), Integer.parseInt(fields.get(7)));
    }

    public RemoteObjectDebugInfo debug(RemoteObject object) {
        requireObject(object);
        List<String> fields = TextWireCodec.decode(executeForOutput(CommandLine.of(
                "object", "debug", Long.toString(object.remoteId()))), 8);
        return new RemoteObjectDebugInfo(
                Long.parseLong(fields.get(0)), fields.get(1), fields.get(2), fields.get(3),
                fields.get(4), Integer.parseInt(fields.get(5)), Integer.parseInt(fields.get(6)), fields.get(7));
    }

    // Lightweight textual JNI calls remain useful for scripts and diagnostics.
    public String readStaticFields(String className) {
        return executeForOutput(CommandLine.of("jni", "fields", className));
    }

    public String readStaticField(String className, String fieldName) {
        return executeForOutput(CommandLine.of("jni", "get", className, fieldName));
    }

    public String callStaticMethod(
            String className, String methodName, String descriptor, String... arguments) {
        List<String> command = objectCommand("call", className, methodName, descriptor);
        Collections.addAll(command, arguments);
        return executeForOutput(CommandLine.of("jni", command.toArray(new String[0])));
    }

    private RemoteObject object(String encoded) {
        RemoteObjectDescriptor descriptor = RemoteObjectDescriptor.decode(encoded);
        return new RemoteObject(
                server(), descriptor.id(), this, descriptor.className(),
                descriptor.nullValue(), descriptor.displayValue());
    }

    private static List<String> objectCommand(String... values) {
        List<String> result = new ArrayList<String>();
        Collections.addAll(result, values);
        return result;
    }

    private void addIds(List<String> command, RemoteObject[] objects) {
        for (RemoteObject object : objects) {
            requireObject(object);
            command.add(Long.toString(object.remoteId()));
        }
    }

    private String id(RemoteObject object) {
        if (object == null) return "0";
        requireObject(object);
        return Long.toString(object.remoteId());
    }

    private void requireObject(RemoteObject object) {
        if (object == null) throw new IllegalArgumentException("Remote argument must not be null; use valueOf(null)");
        if (object.server() != server()) throw new IllegalArgumentException("Remote object belongs to another session");
        if (object.isReleased()) throw new IllegalStateException("Remote object has been released: " + object.remoteId());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }
}
