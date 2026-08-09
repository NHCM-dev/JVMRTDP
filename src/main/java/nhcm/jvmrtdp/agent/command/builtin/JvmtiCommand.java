package nhcm.jvmrtdp.agent.command.builtin;

import nhcm.jvmrtdp.agent.NativeAgent;
import nhcm.jvmrtdp.api.jvmti.JvmtiEventType;
import nhcm.jvmrtdp.api.jvmti.JvmtiCapabilityStatus;
import nhcm.jvmrtdp.agent.command.RemoteCommand;
import nhcm.jvmrtdp.handles.JRDHandle;
import nhcm.jvmrtdp.protocol.CommandReply;
import nhcm.jvmrtdp.protocol.RemoteObjectDescriptor;
import nhcm.jvmrtdp.protocol.TextWireCodec;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Target-side JVMTI operation surface. */
public class JvmtiCommand implements RemoteCommand {
    @Override
    public String name() {
        return "jvmti";
    }

    @Override
    public String usage() {
        return "jvmti <bytes|capabilities|capability-status|events|retransform|redefine|breakpoint|watch|threads|"
                + "thread.state|thread.stack|thread.suspend|thread.resume|thread.interrupt|thread.frame-pop|"
                + "object.size|tag.get|tag.set|gc|properties> ...";
    }

    @Override
    public String description() {
        return "Exposes class, thread, tag, heap and runtime operations from the target JVMTI environment.";
    }

    @Override
    public CommandReply execute(JRDHandle handle, List<String> arguments) {
        if (arguments.isEmpty()) return invalid();
        String operation = arguments.get(0).toLowerCase(Locale.ROOT);
        if ("bytes".equals(operation) && arguments.size() == 2) {
            return success(Base64.getUrlEncoder().withoutPadding().encodeToString(
                    handle.targetJvm().getClassBytes(arguments.get(1))));
        }
        if ("capabilities".equals(operation) && arguments.size() == 1) {
            return success(NativeAgent.capabilities());
        }
        if ("capability-status".equals(operation) && arguments.size() == 1) {
            List<String> rows = new ArrayList<String>();
            for (JvmtiCapabilityStatus status : NativeAgent.capabilityStatuses()) {
                rows.add(TextWireCodec.encode(status.capability().wireName(),
                        Boolean.toString(status.enabled()), Boolean.toString(status.potential())));
            }
            return success(join(rows));
        }
        if ("events".equals(operation) && arguments.size() == 1) {
            List<String> names = new ArrayList<String>();
            for (JvmtiEventType type : JvmtiEventType.values()) names.add(type.wireName());
            return success(join(names));
        }
        if ("retransform".equals(operation) && arguments.size() == 2) {
            NativeAgent.retransformClass(NativeAgent.findLoadedClass(arguments.get(1)));
            return success("ok");
        }
        if ("redefine".equals(operation) && arguments.size() == 3) {
            NativeAgent.redefineClass(NativeAgent.findLoadedClass(arguments.get(1)),
                    Base64.getUrlDecoder().decode(arguments.get(2)));
            return success("ok");
        }
        if ("breakpoint".equals(operation) && arguments.size() == 6) {
            boolean enabled = toggle(arguments.get(1));
            NativeAgent.setBreakpoint(NativeAgent.findLoadedClass(arguments.get(2)), arguments.get(3),
                    arguments.get(4), Long.parseLong(arguments.get(5)), enabled);
            return success("ok");
        }
        if ("watch".equals(operation) && arguments.size() == 6) {
            boolean modification;
            if ("access".equalsIgnoreCase(arguments.get(1))) modification = false;
            else if ("modification".equalsIgnoreCase(arguments.get(1))) modification = true;
            else throw new IllegalArgumentException("Watch kind must be access or modification");
            boolean enabled = toggle(arguments.get(2));
            NativeAgent.setFieldWatch(NativeAgent.findLoadedClass(arguments.get(3)), arguments.get(4),
                    arguments.get(5), modification, enabled);
            return success("ok");
        }
        if ("threads".equals(operation) && arguments.size() == 1) {
            List<String> rows = new ArrayList<String>();
            for (Thread thread : NativeAgent.getAllThreads()) {
                RemoteObjectDescriptor descriptor = handle.targetObjects().storeExternal(thread);
                rows.add(TextWireCodec.encode(descriptor.encode(),
                        Integer.toString(NativeAgent.getThreadState(thread))));
            }
            return success(join(rows));
        }
        if ("thread.state".equals(operation) && arguments.size() == 2) {
            return success(Integer.toString(NativeAgent.getThreadState(thread(handle, arguments.get(1)))));
        }
        if ("thread.stack".equals(operation) && arguments.size() == 3) {
            String[] frames = NativeAgent.getStackTrace(thread(handle, arguments.get(1)),
                    Integer.parseInt(arguments.get(2)));
            List<String> rows = new ArrayList<String>();
            for (String frame : frames) rows.add(TextWireCodec.encode(frame));
            return success(join(rows));
        }
        if (("thread.suspend".equals(operation) || "thread.resume".equals(operation)
                || "thread.interrupt".equals(operation)) && arguments.size() == 2) {
            Thread thread = thread(handle, arguments.get(1));
            if ("thread.suspend".equals(operation)) {
                if (thread == Thread.currentThread()) {
                    throw new IllegalArgumentException("Refusing to suspend the JVMRTDP command thread");
                }
                NativeAgent.suspendThread(thread);
            }
            else if ("thread.resume".equals(operation)) NativeAgent.resumeThread(thread);
            else NativeAgent.interruptThread(thread);
            return success("ok");
        }
        if ("thread.frame-pop".equals(operation) && arguments.size() == 3) {
            NativeAgent.notifyFramePop(thread(handle, arguments.get(1)), Integer.parseInt(arguments.get(2)));
            return success("ok");
        }
        if ("object.size".equals(operation) && arguments.size() == 2) {
            return success(Long.toString(NativeAgent.getObjectSize(object(handle, arguments.get(1)))));
        }
        if ("tag.get".equals(operation) && arguments.size() == 2) {
            return success(Long.toString(NativeAgent.getTag(object(handle, arguments.get(1)))));
        }
        if ("tag.set".equals(operation) && arguments.size() == 3) {
            NativeAgent.setTag(object(handle, arguments.get(1)), Long.parseLong(arguments.get(2)));
            return success("ok");
        }
        if ("gc".equals(operation) && arguments.size() == 1) {
            NativeAgent.forceGarbageCollection();
            return success("ok");
        }
        if ("properties".equals(operation) && arguments.size() == 1) {
            List<String> rows = new ArrayList<String>();
            for (String property : NativeAgent.systemProperties()) rows.add(TextWireCodec.encode(property));
            return success(join(rows));
        }
        return invalid();
    }

    private static Object object(JRDHandle handle, String id) {
        return handle.targetObjects().resolveExternal(Long.parseLong(id));
    }

    private static Thread thread(JRDHandle handle, String id) {
        Object value = object(handle, id);
        if (!(value instanceof Thread)) throw new IllegalArgumentException("Object is not a Thread: " + id);
        return (Thread) value;
    }

    private CommandReply invalid() {
        return new CommandReply(false, "Usage: " + usage());
    }

    private static CommandReply success(String output) {
        return new CommandReply(true, output);
    }

    private static String join(List<String> rows) {
        return String.join(System.lineSeparator(), rows);
    }

    private static boolean toggle(String value) {
        if ("set".equalsIgnoreCase(value) || "enable".equalsIgnoreCase(value)) return true;
        if ("clear".equalsIgnoreCase(value) || "disable".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("Operation must be set/enable or clear/disable");
    }
}
