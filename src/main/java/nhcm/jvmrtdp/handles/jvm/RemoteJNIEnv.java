package nhcm.jvmrtdp.handles.jvm;

import nhcm.jvmrtdp.handles.RemoteHandle;
import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.command.CommandLine;
import nhcm.jvmrtdp.handles.java.RemoteClass;
import nhcm.jvmrtdp.handles.java.RemoteField;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RemoteJNIEnv extends RemoteHandle {
    public RemoteJNIEnv(ServerHandle server, long remoteId) {
        super(server, remoteId);
    }

    public RemoteClass findClass(String className) {
        return new RemoteClass(server(), allocateRemoteId(), className, this, server().javaVM().jvmtiEnv());
    }

    public String readStaticFields(String className) {
        return execute(CommandLine.of("jni", "fields", className));
    }

    public String readStaticField(String className, String fieldName) {
        return execute(CommandLine.of("jni", "get", className, fieldName));
    }

    public String callStaticMethod(
            String className, String methodName, String descriptor, String... arguments) {
        List<String> tokens = new ArrayList<String>();
        tokens.add("call");
        tokens.add(className);
        tokens.add(methodName);
        tokens.add(descriptor);
        tokens.addAll(Arrays.asList(arguments));
        return execute(CommandLine.of("jni", tokens.toArray(new String[0])));
    }

    public List<RemoteField> listStaticFields(RemoteClass owner) {
        String output = readStaticFields(owner.className());
        List<RemoteField> fields = new ArrayList<RemoteField>();
        if ("<no declared static fields>".equals(output) || output.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        for (String line : output.split("\\r?\\n")) {
            String[] columns = line.split("\\t", 3);
            if (columns.length != 3) {
                throw new IllegalStateException("Target returned an invalid field row: " + line);
            }
            fields.add(new RemoteField(
                    server(), allocateRemoteId(), owner, columns[0], columns[1], columns[2]));
        }
        return java.util.Collections.unmodifiableList(fields);
    }

    public RemoteField getStaticField(RemoteClass owner, String fieldName) {
        String output = readStaticField(owner.className(), fieldName);
        String[] columns = output.split("\\t", 2);
        if (columns.length != 2) {
            throw new IllegalStateException("Target returned an invalid field value: " + output);
        }
        return new RemoteField(
                server(), allocateRemoteId(), owner, fieldName, columns[0], columns[1]);
    }

    private String execute(String commandLine) {
        return executeForOutput(commandLine);
    }
}
