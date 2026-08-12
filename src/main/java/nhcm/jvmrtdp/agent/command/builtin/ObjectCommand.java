package nhcm.jvmrtdp.agent.command.builtin;

import nhcm.jvmrtdp.agent.command.RemoteCommand;
import nhcm.jvmrtdp.handles.JRDHandle;
import nhcm.jvmrtdp.protocol.CommandReply;
import nhcm.jvmrtdp.remoteside.TargetObjectService;

import java.util.List;

public class ObjectCommand implements RemoteCommand {
    @Override
    public String name() {
        return "object";
    }

    @Override
    public String usage() {
        return "object <value|construct|methods|fields|constructors|class.info|class.names|system.property|class.load|class.load.start|package|class.search|"
                + "package.search|field.search|method.search|call|call.special|"
                + "field.get|field.set|instanceof|array.length|array.get|array.set|iterable|map|stats|debug|as|release> ...";
    }

    @Override
    public String description() {
        return "Internal object-handle operations used by RemoteClass/RemoteObject.";
    }

    @Override
    public CommandReply execute(JRDHandle handle, List<String> arguments) {
        if (arguments.isEmpty()) return invalidUsage();
        TargetObjectService objects = handle.targetObjects();
        String operation = arguments.get(0).toLowerCase(java.util.Locale.ROOT);
        if ("value".equals(operation) && arguments.size() == 3) {
            return success(objects.value(arguments.get(1), arguments.get(2)).encode());
        }
        if ("construct".equals(operation) && arguments.size() >= 3) {
            return success(objects.construct(
                    arguments.get(1), arguments.get(2), ids(arguments, 3)).encode());
        }
        if ("methods".equals(operation) && arguments.size() == 3) {
            return success(join(objects.methods(arguments.get(1), mode(arguments.get(2), "static"))));
        }
        if ("fields".equals(operation) && arguments.size() == 3) {
            return success(join(objects.fields(arguments.get(1), mode(arguments.get(2), "static"))));
        }
        if ("constructors".equals(operation) && arguments.size() == 2) {
            return success(join(objects.constructors(arguments.get(1))));
        }
        if ("class.info".equals(operation) && arguments.size() == 2) {
            return success(objects.classInfo(arguments.get(1)));
        }
        if ("class.names".equals(operation) && arguments.size() == 1) {
            return success(join(objects.loadedClassNames()));
        }
        if ("system.property".equals(operation) && arguments.size() == 2) {
            return success(objects.systemProperty(arguments.get(1)));
        }
        if ("class.load".equals(operation) && arguments.size() == 2) {
            return success(objects.forceLoadClass(arguments.get(1)).getName());
        }
        if ("class.load.start".equals(operation) && arguments.size() == 2) {
            return success(objects.startForceLoadClass(arguments.get(1)).getName());
        }
        if ("package".equals(operation) && arguments.size() == 2) {
            return success(join(objects.packageContents(arguments.get(1))));
        }
        if ("class.search".equals(operation) && arguments.size() == 7) {
            return success(join(objects.searchClasses(
                    arguments.get(1), arguments.get(2), arguments.get(3),
                    arguments.get(4), arguments.get(5), limit(arguments.get(6)))));
        }
        if ("package.search".equals(operation) && arguments.size() == 3) {
            return success(join(objects.searchPackages(arguments.get(1), limit(arguments.get(2)))));
        }
        if ("field.search".equals(operation) && arguments.size() == 6) {
            return success(join(objects.searchFields(
                    arguments.get(1), arguments.get(2), arguments.get(3),
                    arguments.get(4), limit(arguments.get(5)))));
        }
        if ("method.search".equals(operation) && arguments.size() == 7) {
            return success(join(objects.searchMethods(
                    arguments.get(1), arguments.get(2), arguments.get(3), arguments.get(4),
                    arguments.get(5), limit(arguments.get(6)))));
        }
        if ("call".equals(operation) && arguments.size() >= 5) {
            return success(objects.call(
                    arguments.get(1), arguments.get(2), arguments.get(3),
                    id(arguments.get(4)), ids(arguments, 5)).encode());
        }
        if ("call.special".equals(operation) && arguments.size() >= 5) {
            return success(objects.callSpecial(
                    arguments.get(1), arguments.get(2), arguments.get(3),
                    id(arguments.get(4)), ids(arguments, 5)).encode());
        }
        if ("field.get".equals(operation) && arguments.size() == 5) {
            return success(objects.readField(
                    arguments.get(1), arguments.get(2), arguments.get(3), id(arguments.get(4))).encode());
        }
        if ("field.set".equals(operation) && arguments.size() == 6) {
            objects.writeField(
                    arguments.get(1), arguments.get(2), arguments.get(3),
                    id(arguments.get(4)), id(arguments.get(5)));
            return success("ok");
        }
        if ("class".equals(operation) && arguments.size() == 2) {
            return success(objects.className(id(arguments.get(1))));
        }
        if ("instanceof".equals(operation) && arguments.size() == 3) {
            return success(Boolean.toString(objects.isInstance(arguments.get(1), id(arguments.get(2)))));
        }
        if ("as".equals(operation) && arguments.size() == 2) {
            return success(objects.materialize(id(arguments.get(1))));
        }
        if ("describe".equals(operation) && arguments.size() == 2) {
            return success(objects.describe(id(arguments.get(1))).encode());
        }
        if ("array.length".equals(operation) && arguments.size() == 2) {
            return success(Integer.toString(objects.arrayLength(id(arguments.get(1)))));
        }
        if ("array.get".equals(operation) && arguments.size() == 3) {
            return success(objects.arrayGet(id(arguments.get(1)), index(arguments.get(2))).encode());
        }
        if ("array.set".equals(operation) && arguments.size() == 4) {
            objects.arraySet(id(arguments.get(1)), index(arguments.get(2)), id(arguments.get(3)));
            return success("ok");
        }
        if ("iterable".equals(operation) && arguments.size() == 3) {
            return success(join(objects.iterableElements(id(arguments.get(1)), limit(arguments.get(2)))));
        }
        if ("map".equals(operation) && arguments.size() == 3) {
            return success(join(objects.mapEntries(id(arguments.get(1)), limit(arguments.get(2)))));
        }
        if ("stats".equals(operation) && arguments.size() == 1) {
            return success(objects.statistics());
        }
        if ("debug".equals(operation) && arguments.size() == 2) {
            return success(objects.debug(id(arguments.get(1))));
        }
        if ("release".equals(operation) && arguments.size() >= 2) {
            for (int index = 1; index < arguments.size(); index++) objects.release(id(arguments.get(index)));
            return success("ok");
        }
        return invalidUsage();
    }

    private CommandReply invalidUsage() {
        return new CommandReply(false, "Usage: " + usage());
    }

    private static CommandReply success(String output) {
        return new CommandReply(true, output);
    }

    private static boolean mode(String actual, String trueMode) {
        if (trueMode.equalsIgnoreCase(actual)) return true;
        if ("virtual".equalsIgnoreCase(actual)) return false;
        throw new IllegalArgumentException("Mode must be static or virtual: " + actual);
    }

    private static long id(String value) {
        long id = Long.parseLong(value);
        if (id < 0) throw new IllegalArgumentException("Object ID must not be negative: " + value);
        return id;
    }

    private static int index(String value) {
        int index = Integer.parseInt(value);
        if (index < 0) throw new IllegalArgumentException("Index must not be negative: " + value);
        return index;
    }

    private static int limit(String value) {
        return Integer.parseInt(value);
    }

    private static long[] ids(List<String> arguments, int start) {
        long[] result = new long[arguments.size() - start];
        for (int index = start; index < arguments.size(); index++) {
            result[index - start] = id(arguments.get(index));
        }
        return result;
    }

    private static String join(List<String> rows) {
        return String.join(System.lineSeparator(), rows);
    }
}
