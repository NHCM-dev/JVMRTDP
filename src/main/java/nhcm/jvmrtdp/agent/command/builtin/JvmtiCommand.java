package nhcm.jvmrtdp.agent.command.builtin;

import nhcm.jvmrtdp.agent.NativeAgent;
import nhcm.jvmrtdp.api.jvmti.JvmtiEventType;
import nhcm.jvmrtdp.api.jvmti.JvmtiCapabilityStatus;
import nhcm.jvmrtdp.api.jvmti.JvmtiCapability;
import nhcm.jvmrtdp.agent.command.RemoteCommand;
import nhcm.jvmrtdp.handles.JRDHandle;
import nhcm.jvmrtdp.protocol.CommandReply;
import nhcm.jvmrtdp.protocol.RemoteObjectDescriptor;
import nhcm.jvmrtdp.protocol.TextWireCodec;
import nhcm.jvmrtdp.remoteside.StringAllocationHookService;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.IdentityHashMap;

/** Target-side JVMTI operation surface. */
public class JvmtiCommand implements RemoteCommand {
    @Override
    public String name() {
        return "jvmti";
    }

    @Override
    public String usage() {
        return "jvmti <bytes|capabilities|capability-status|capability.add|capability.relinquish|"
                + "phase|time|timer-info|current-thread.cpu-time|processors|location-format|"
                + "class.info|class.interfaces|class.loader-classes|class.source-debug|class.constant-pool|"
                + "method.info|method.bytecodes|method.lines|field.info|events|events.generate|verbose|retransform|redefine|"
                + "breakpoint|debug.event-breakpoint|debug.enable|debug.disable|debug.status|debug.status-all|debug.continue|"
                + "debug.pause-thread|debug.continue-thread|debug.continue-all|debug.step|debug.step-thread|debug.step-out|debug.step-out-thread|"
                + "debug.locals|debug.set-local|debug.force-return|debug.force-return-void|string.alloc|"
                + "watch|threads|thread.info|thread.state|thread.stack|thread.frame-count|"
                + "thread.cpu-time|thread.owned-monitors|thread.contended-monitor|thread.suspend|thread.resume|"
                + "thread.interrupt|thread.frame-pop|object.size|object.hash|object.monitor-usage|"
                + "tag.get|tag.set|tag.objects|gc|properties|property.get|property.set> ...";
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
        if ("string.alloc".equals(operation) && arguments.size() >= 3) {
            String action = arguments.get(1).toLowerCase(Locale.ROOT);
            String id = arguments.get(2);
            if ("clear".equals(action)) {
                return success(Boolean.toString(StringAllocationHookService.remove(id)));
            }
            if ("set".equals(action) && arguments.size() == 8) {
                StringAllocationHookService.set(id, arguments.get(3), arguments.get(4),
                        arguments.get(5), arguments.get(6), Boolean.parseBoolean(arguments.get(7)));
                return success("ok");
            }
            return invalid();
        }
        if ("capabilities".equals(operation) && arguments.size() == 1) {
            return success(NativeAgent.capabilities());
        }
        if ("capability-status".equals(operation) && arguments.size() == 1) {
            return capabilityStatusReply();
        }
        if (("capability.add".equals(operation) || "capability.relinquish".equals(operation))
                && arguments.size() >= 2) {
            JvmtiCapability[] requested = new JvmtiCapability[arguments.size() - 1];
            for (int index = 1; index < arguments.size(); index++) {
                requested[index - 1] = JvmtiCapability.parse(arguments.get(index));
            }
            if ("capability.add".equals(operation)) NativeAgent.addCapabilities(requested);
            else NativeAgent.relinquishCapabilities(requested);
            return capabilityStatusReply();
        }
        if ("phase".equals(operation) && arguments.size() == 1) {
            return success(NativeAgent.phase().name());
        }
        if ("time".equals(operation) && arguments.size() == 1) {
            return success(Long.toString(NativeAgent.time()));
        }
        if ("timer-info".equals(operation) && arguments.size() == 1) {
            return success(TextWireCodec.encode(NativeAgent.timerInfo()));
        }
        if ("current-thread.cpu-time".equals(operation) && arguments.size() == 1) {
            return success(Long.toString(NativeAgent.currentThreadCpuTime()));
        }
        if ("processors".equals(operation) && arguments.size() == 1) {
            return success(Integer.toString(NativeAgent.availableProcessors()));
        }
        if ("location-format".equals(operation) && arguments.size() == 1) {
            return success(NativeAgent.locationFormat().name());
        }
        if ("property.get".equals(operation) && arguments.size() == 2) {
            return success(NativeAgent.getSystemProperty(arguments.get(1)));
        }
        if ("property.set".equals(operation) && arguments.size() == 3) {
            NativeAgent.setSystemProperty(arguments.get(1), arguments.get(2));
            return success(NativeAgent.getSystemProperty(arguments.get(1)));
        }
        if ("class.info".equals(operation) && arguments.size() == 2) {
            String[] info = NativeAgent.classInfo(NativeAgent.findLoadedClass(arguments.get(1)));
            return success(TextWireCodec.encode(arguments.get(1), info[0], info[1], info[2],
                    info[3], info[4], info[5], info[6], info[7], info[8], info[9]));
        }
        if ("class.interfaces".equals(operation) && arguments.size() == 2) {
            List<String> rows = new ArrayList<String>();
            for (Class<?> type : NativeAgent.implementedInterfaces(
                    NativeAgent.findLoadedClass(arguments.get(1)))) {
                rows.add(TextWireCodec.encode(type.getName()));
            }
            return success(join(rows));
        }
        if ("class.loader-classes".equals(operation) && arguments.size() == 2) {
            Class<?> type = NativeAgent.findLoadedClass(arguments.get(1));
            List<String> rows = new ArrayList<String>();
            for (Class<?> loaded : NativeAgent.classLoaderClasses(NativeAgent.classLoader(type))) {
                rows.add(TextWireCodec.encode(loaded.getName()));
            }
            return success(join(rows));
        }
        if ("method.info".equals(operation) && arguments.size() == 4) {
            String[] info = NativeAgent.methodInfo(NativeAgent.findLoadedClass(arguments.get(1)),
                    arguments.get(2), arguments.get(3));
            return success(TextWireCodec.encode(arguments.get(1), arguments.get(2), arguments.get(3),
                    info[0], info[1], info[2], info[3], info[4], info[5], info[6], info[7], info[8]));
        }
        if ("method.bytecodes".equals(operation) && arguments.size() == 4) {
            return success(Base64.getUrlEncoder().withoutPadding().encodeToString(
                    NativeAgent.methodBytecodes(NativeAgent.findLoadedClass(arguments.get(1)),
                            arguments.get(2), arguments.get(3))));
        }
        if ("method.lines".equals(operation) && arguments.size() == 4) {
            List<String> rows = new ArrayList<String>();
            for (String line : NativeAgent.lineNumberTable(NativeAgent.findLoadedClass(arguments.get(1)),
                    arguments.get(2), arguments.get(3))) {
                String[] fields = line.split("\\|", -1);
                rows.add(TextWireCodec.encode(fields[0], fields[1]));
            }
            return success(join(rows));
        }
        if ("field.info".equals(operation) && arguments.size() == 4) {
            String[] info = NativeAgent.fieldInfo(NativeAgent.findLoadedClass(arguments.get(1)),
                    arguments.get(2), arguments.get(3));
            return success(TextWireCodec.encode(arguments.get(1), arguments.get(2), arguments.get(3),
                    info[0], info[1], info[2], info[3]));
        }
        if ("class.source-debug".equals(operation) && arguments.size() == 2) {
            return success(NativeAgent.sourceDebugExtension(NativeAgent.findLoadedClass(arguments.get(1))));
        }
        if ("class.constant-pool".equals(operation) && arguments.size() == 2) {
            return success(Base64.getUrlEncoder().withoutPadding().encodeToString(
                    NativeAgent.constantPool(NativeAgent.findLoadedClass(arguments.get(1)))));
        }
        if ("events.generate".equals(operation) && arguments.size() == 2) {
            NativeAgent.generateEvents(arguments.get(1));
            return success("ok");
        }
        if ("verbose".equals(operation) && arguments.size() == 3) {
            NativeAgent.setVerboseFlag(arguments.get(1), toggle(arguments.get(2)));
            return success("ok");
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
        if ("breakpoint".equals(operation)
                && (arguments.size() == 6 || arguments.size() == 11)) {
            boolean enabled = toggle(arguments.get(1));
            String registrationId = arguments.size() == 11 ? arguments.get(6)
                    : arguments.get(2) + '|' + arguments.get(3) + '|'
                            + arguments.get(4) + '|' + arguments.get(5);
            Object receiver = arguments.size() == 11 && !"0".equals(arguments.get(7))
                    ? handle.targetObjects().resolveExternal(Long.parseLong(arguments.get(7))) : null;
            if (receiver == null) {
                NativeAgent.setBreakpoint(arguments.get(2), arguments.get(3), arguments.get(4),
                        Long.parseLong(arguments.get(5)), enabled, registrationId,
                        optionalPattern(arguments, 8), optionalPattern(arguments, 9),
                        optionalPattern(arguments, 10));
            } else {
                NativeAgent.setBreakpoint(NativeAgent.findLoadedClass(arguments.get(2)), arguments.get(3),
                        arguments.get(4), Long.parseLong(arguments.get(5)), enabled, registrationId,
                        receiver, optionalPattern(arguments, 8), optionalPattern(arguments, 9),
                        optionalPattern(arguments, 10));
            }
            return success("ok");
        }
        if ("debug.enable".equals(operation) && arguments.size() == 1) {
            NativeAgent.configureDebugger(true);
            return success("ok");
        }
        if ("debug.disable".equals(operation) && arguments.size() == 1) {
            NativeAgent.configureDebugger(false);
            return success("ok");
        }
        if ("debug.event-breakpoint".equals(operation) && arguments.size() == 8) {
            boolean enabled = toggle(arguments.get(1));
            int kind;
            if ("entry".equalsIgnoreCase(arguments.get(2))) kind = 0;
            else if ("exit".equalsIgnoreCase(arguments.get(2))) kind = 1;
            else if ("exception".equalsIgnoreCase(arguments.get(2))) kind = 2;
            else throw new IllegalArgumentException("Event breakpoint kind must be entry, exit, or exception");
            boolean includeSubtypes = Boolean.parseBoolean(arguments.get(6));
            Class<?> declaredType = null;
            if (enabled && includeSubtypes && kind != 2) {
                try {
                    declaredType = NativeAgent.findLoadedClass(arguments.get(3));
                } catch (IllegalArgumentException unloaded) {
                    // ClassPrepare binds the exact declared type later. Until then the
                    // class-name match already catches the declared method itself.
                }
            }
            NativeAgent.setDebugEventBreakpoint(kind, declaredType, arguments.get(3),
                    optionalPattern(arguments, 4), optionalPattern(arguments, 5),
                    includeSubtypes, arguments.get(7), enabled);
            return success("ok");
        }
        if ("debug.status".equals(operation) && arguments.size() == 1) {
            return success(encodeDebuggerState(handle, NativeAgent.debuggerSnapshot()));
        }
        if ("debug.status-all".equals(operation) && arguments.size() == 1) {
            List<String> rows = new ArrayList<String>();
            for (Object[] state : NativeAgent.debuggerSnapshots()) {
                rows.add(encodeDebuggerState(handle, state));
            }
            return success(join(rows));
        }
        if (("debug.continue".equals(operation) || "debug.step".equals(operation))
                && arguments.size() == 1) {
            NativeAgent.resumeDebugger("debug.step".equals(operation));
            return success("ok");
        }
        if (("debug.continue-thread".equals(operation) || "debug.step-thread".equals(operation))
                && arguments.size() == 2) {
            NativeAgent.resumeDebugger(thread(handle, arguments.get(1)),
                    "debug.step-thread".equals(operation));
            return success("ok");
        }
        if ("debug.step-out".equals(operation) && arguments.size() == 1) {
            NativeAgent.stepOutDebugger();
            return success("ok");
        }
        if ("debug.step-out-thread".equals(operation) && arguments.size() == 2) {
            NativeAgent.stepOutDebugger(thread(handle, arguments.get(1)));
            return success("ok");
        }
        if ("debug.pause-thread".equals(operation)
                && (arguments.size() == 2 || arguments.size() == 3)) {
            NativeAgent.pauseDebugger(thread(handle, arguments.get(1)),
                    arguments.size() == 3 ? arguments.get(2) : "manual_pause");
            return success("ok");
        }
        if ("debug.continue-all".equals(operation) && arguments.size() == 1) {
            NativeAgent.resumeDebugger(null, false);
            return success("ok");
        }
        if ("debug.locals".equals(operation) && arguments.size() == 3) {
            List<String> rows = new ArrayList<String>();
            Object[][] locals = NativeAgent.debuggerLocals(thread(handle, arguments.get(1)),
                    Integer.parseInt(arguments.get(2)));
            for (Object[] local : locals) {
                RemoteObjectDescriptor value = handle.targetObjects().storeExternalOpaque(local[6]);
                rows.add(TextWireCodec.encode(String.valueOf(local[0]), String.valueOf(local[1]),
                        String.valueOf(local[2]), String.valueOf(local[3]), String.valueOf(local[4]),
                        String.valueOf(local[5]), value.encode(),
                        local[7] == null ? "" : String.valueOf(local[7])));
            }
            return success(join(rows));
        }
        if ("debug.set-local".equals(operation) && arguments.size() == 6) {
            NativeAgent.setDebuggerLocal(thread(handle, arguments.get(1)),
                    Integer.parseInt(arguments.get(2)), Integer.parseInt(arguments.get(3)),
                    arguments.get(4), handle.targetObjects().resolveExternal(
                            Long.parseLong(arguments.get(5))));
            return success("ok");
        }
        if ("debug.force-return".equals(operation) && arguments.size() == 3) {
            NativeAgent.forceDebuggerReturn(thread(handle, arguments.get(1)),
                    handle.targetObjects().resolveExternal(Long.parseLong(arguments.get(2))));
            return success("ok");
        }
        if ("debug.force-return-void".equals(operation) && arguments.size() == 2) {
            NativeAgent.forceDebuggerReturn(thread(handle, arguments.get(1)), null);
            return success("ok");
        }
        if ("watch".equals(operation) && (arguments.size() == 6 || arguments.size() == 8)) {
            boolean modification;
            if ("access".equalsIgnoreCase(arguments.get(1))) modification = false;
            else if ("modification".equalsIgnoreCase(arguments.get(1))) modification = true;
            else throw new IllegalArgumentException("Watch kind must be access or modification");
            boolean enabled = toggle(arguments.get(2));
            String registrationId = arguments.size() == 8 ? arguments.get(6)
                    : arguments.get(3) + '|' + arguments.get(4) + '|'
                            + arguments.get(5) + '|' + arguments.get(1);
            Object receiver = arguments.size() == 8 && !"0".equals(arguments.get(7))
                    ? handle.targetObjects().resolveExternal(Long.parseLong(arguments.get(7))) : null;
            if (receiver == null) {
                NativeAgent.setFieldWatch(arguments.get(3), arguments.get(4), arguments.get(5),
                        modification, enabled, registrationId);
            } else {
                NativeAgent.setFieldWatch(NativeAgent.findLoadedClass(arguments.get(3)), arguments.get(4),
                        arguments.get(5), modification, enabled, registrationId, receiver);
            }
            return success("ok");
        }
        if ("threads".equals(operation) && arguments.size() == 1) {
            List<String> rows = new ArrayList<String>();
            IdentityHashMap<Thread, Boolean> debuggerPaused = new IdentityHashMap<Thread, Boolean>();
            for (Object[] state : NativeAgent.debuggerSnapshots()) {
                if (state.length > 2 && state[0] instanceof Thread
                        && Boolean.parseBoolean(String.valueOf(state[2]))) {
                    debuggerPaused.put((Thread) state[0], Boolean.TRUE);
                }
            }
            for (Thread thread : NativeAgent.getAllThreads()) {
                RemoteObjectDescriptor descriptor = handle.targetObjects().storeExternal(thread);
                String[] info;
                try { info = NativeAgent.threadInfo(thread); }
                catch (RuntimeException unavailable) {
                    info = new String[] { thread.getName(), Integer.toString(thread.getPriority()),
                            Boolean.toString(thread.isDaemon()), "", "", "0" };
                }
                rows.add(TextWireCodec.encode(descriptor.encode(),
                        Integer.toString(NativeAgent.getThreadState(thread)), info[0], info[1], info[2],
                        Boolean.toString(debuggerPaused.containsKey(thread))));
            }
            return success(join(rows));
        }
        if ("thread.state".equals(operation) && arguments.size() == 2) {
            return success(Integer.toString(NativeAgent.getThreadState(thread(handle, arguments.get(1)))));
        }
        if ("thread.info".equals(operation) && arguments.size() == 2) {
            String[] info = NativeAgent.threadInfo(thread(handle, arguments.get(1)));
            return success(TextWireCodec.encode(info));
        }
        if ("thread.frame-count".equals(operation) && arguments.size() == 2) {
            return success(Integer.toString(NativeAgent.frameCount(thread(handle, arguments.get(1)))));
        }
        if ("thread.cpu-time".equals(operation) && arguments.size() == 2) {
            return success(Long.toString(NativeAgent.threadCpuTime(thread(handle, arguments.get(1)))));
        }
        if ("thread.owned-monitors".equals(operation) && arguments.size() == 2) {
            List<String> rows = new ArrayList<String>();
            for (Object monitor : NativeAgent.ownedMonitors(thread(handle, arguments.get(1)))) {
                rows.add(handle.targetObjects().storeExternal(monitor).encode());
            }
            return success(join(rows));
        }
        if ("thread.contended-monitor".equals(operation) && arguments.size() == 2) {
            Object monitor = NativeAgent.currentContendedMonitor(thread(handle, arguments.get(1)));
            return success(handle.targetObjects().storeExternal(monitor).encode());
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
        if ("object.hash".equals(operation) && arguments.size() == 2) {
            return success(Integer.toString(NativeAgent.getObjectHashCode(object(handle, arguments.get(1)))));
        }
        if ("object.monitor-usage".equals(operation) && arguments.size() == 2) {
            return success(TextWireCodec.encode(NativeAgent.objectMonitorUsage(
                    object(handle, arguments.get(1)))));
        }
        if ("tag.get".equals(operation) && arguments.size() == 2) {
            return success(Long.toString(NativeAgent.getTag(object(handle, arguments.get(1)))));
        }
        if ("tag.set".equals(operation) && arguments.size() == 3) {
            NativeAgent.setTag(object(handle, arguments.get(1)), Long.parseLong(arguments.get(2)));
            return success("ok");
        }
        if ("tag.objects".equals(operation) && arguments.size() == 2) {
            List<String> rows = new ArrayList<String>();
            for (Object object : NativeAgent.objectsWithTag(Long.parseLong(arguments.get(1)))) {
                rows.add(handle.targetObjects().storeExternal(object).encode());
            }
            return success(join(rows));
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

    private static String encodeDebuggerState(JRDHandle handle, Object[] state) {
        String thread = state[0] == null ? "" : handle.targetObjects().storeExternal(state[0]).encode();
        String returnValue = state.length <= 10 || state[10] == null ? ""
                : handle.targetObjects().storeExternalOpaque(state[10]).encode();
        String returnState = state.length <= 11 || state[11] == null ? "" : String.valueOf(state[11]);
        return TextWireCodec.encode(thread,
                String.valueOf(state[1]), String.valueOf(state[2]), String.valueOf(state[3]),
                String.valueOf(state[4]), String.valueOf(state[5]), String.valueOf(state[6]),
                String.valueOf(state[7]), String.valueOf(state[8]), String.valueOf(state[9]),
                returnValue, returnState);
    }

    private static CommandReply capabilityStatusReply() {
        List<String> rows = new ArrayList<String>();
        for (JvmtiCapabilityStatus status : NativeAgent.capabilityStatuses()) {
            rows.add(TextWireCodec.encode(status.capability().wireName(),
                    Boolean.toString(status.enabled()), Boolean.toString(status.potential())));
        }
        return success(join(rows));
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

    private static String optionalPattern(List<String> arguments, int index) {
        if (index >= arguments.size()) return "";
        String value = arguments.get(index);
        return "-".equals(value) ? "" : value;
    }
}
