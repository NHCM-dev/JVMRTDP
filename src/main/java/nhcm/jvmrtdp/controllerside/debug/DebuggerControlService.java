package nhcm.jvmrtdp.controllerside.debug;

import nhcm.jvmrtdp.handles.jvm.RemoteJVMTIEnv;
import nhcm.jvmrtdp.handles.jvm.RemoteJvmtiThread;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Session-owned debugger coordination shared by CLI and TUI.
 *
 * <p>An analysis freeze only owns threads that this service actually paused. Threads that
 * were suspended or stopped at a breakpoint beforehand are recorded as preserved and are
 * never resumed by {@link #restore()}.</p>
 */
public final class DebuggerControlService implements AutoCloseable {
    private static final int THREAD_STATE_ALIVE = 0x0001;
    private static final int THREAD_STATE_TERMINATED = 0x0002;
    private static final int THREAD_STATE_SUSPENDED = 0x100000;

    private final RemoteJVMTIEnv jvmti;
    private final Map<Long, OwnedThread> owned = new LinkedHashMap<Long, OwnedThread>();
    private final List<DebuggerFreezeReport.Entry> lastEntries =
            new ArrayList<DebuggerFreezeReport.Entry>();
    private boolean active;
    private long generation;

    public DebuggerControlService(RemoteJVMTIEnv jvmti) {
        if (jvmti == null) throw new IllegalArgumentException("jvmti must not be null");
        this.jvmti = jvmti;
    }

    /** Starts a freeze, or pauses newly-created eligible threads in the active freeze. */
    public synchronized DebuggerFreezeReport freeze() {
        DebuggerFreezeReport.Operation operation = active
                ? DebuggerFreezeReport.Operation.REFRESH : DebuggerFreezeReport.Operation.FREEZE;
        if (!active) {
            jvmti.configureDebugger(true);
            active = true;
            generation++;
        }

        List<DebuggerFreezeReport.Entry> entries = new ArrayList<DebuggerFreezeReport.Entry>();
        List<RemoteJvmtiThread> threads = jvmti.threads();
        for (RemoteJvmtiThread thread : threads) {
            boolean retained = false;
            try {
                String exclusion = sensitiveReason(thread.name());
                int state = thread.capturedState();
                if (exclusion != null) {
                    entries.add(entry(thread, DebuggerFreezeReport.Action.EXCLUDED, exclusion));
                } else if ((state & THREAD_STATE_TERMINATED) != 0
                        || (state & THREAD_STATE_ALIVE) == 0) {
                    entries.add(entry(thread, DebuggerFreezeReport.Action.EXCLUDED, "not a live thread"));
                } else if (thread.debuggerPaused()) {
                    entries.add(entry(thread, DebuggerFreezeReport.Action.PRESERVED,
                            "already paused before this freeze or by an earlier freeze pass"));
                } else if ((state & THREAD_STATE_SUSPENDED) != 0) {
                    entries.add(entry(thread, DebuggerFreezeReport.Action.PRESERVED,
                            "already JVMTI-suspended before this freeze"));
                } else {
                    try {
                        thread.pauseInDebugger();
                        long handle = thread.object().remoteId();
                        owned.put(handle, new OwnedThread(thread, state, thread.stateSummary()));
                        retained = true;
                        entries.add(entry(thread, DebuggerFreezeReport.Action.FROZEN,
                                "paused by analysis freeze generation " + generation));
                    } catch (RuntimeException failure) {
                        String message = rootMessage(failure);
                        entries.add(entry(thread, nonDebuggable(message)
                                        ? DebuggerFreezeReport.Action.EXCLUDED
                                        : DebuggerFreezeReport.Action.FAILED,
                                nonDebuggable(message) ? "no debuggable Java frame: " + message : message));
                    }
                }
            } finally {
                if (!retained) thread.close();
            }
        }
        lastEntries.clear();
        lastEntries.addAll(entries);
        return report(operation, entries);
    }

    /** Restores only threads that were newly paused by this service. */
    public synchronized DebuggerFreezeReport restore() {
        List<DebuggerFreezeReport.Entry> entries = new ArrayList<DebuggerFreezeReport.Entry>();
        Iterator<Map.Entry<Long, OwnedThread>> iterator = owned.entrySet().iterator();
        while (iterator.hasNext()) {
            OwnedThread record = iterator.next().getValue();
            boolean release = false;
            try {
                jvmti.continueExecution(record.thread.object());
                entries.add(record.entry(DebuggerFreezeReport.Action.RESTORED,
                        "resumed to its pre-freeze execution point"));
                release = true;
            } catch (RuntimeException failure) {
                String message = rootMessage(failure);
                if (alreadyReleased(message)) {
                    entries.add(record.entry(DebuggerFreezeReport.Action.RESTORED,
                            "thread had already resumed or terminated"));
                    release = true;
                } else {
                    entries.add(record.entry(DebuggerFreezeReport.Action.FAILED, message));
                }
            }
            if (release) {
                record.close();
                iterator.remove();
            }
        }
        active = !owned.isEmpty();
        lastEntries.clear();
        lastEntries.addAll(entries);
        return report(DebuggerFreezeReport.Operation.RESTORE, entries);
    }

    public synchronized DebuggerFreezeReport status() {
        List<DebuggerFreezeReport.Entry> entries = new ArrayList<DebuggerFreezeReport.Entry>();
        for (OwnedThread record : owned.values()) {
            entries.add(record.entry(DebuggerFreezeReport.Action.ACTIVE,
                    "owned by analysis freeze generation " + generation));
        }
        for (DebuggerFreezeReport.Entry entry : lastEntries) {
            if (entry.action() == DebuggerFreezeReport.Action.EXCLUDED
                    || entry.action() == DebuggerFreezeReport.Action.FAILED) entries.add(entry);
        }
        return report(DebuggerFreezeReport.Operation.STATUS, entries);
    }

    public synchronized boolean active() { return active; }
    public synchronized int ownedThreadCount() { return owned.size(); }

    static String sensitiveReason(String threadName) {
        String name = threadName == null ? "" : threadName.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("jvmrtdp")) return "JVMRTDP command/event service thread";
        if (name.equals("attach listener") || name.equals("signal dispatcher")
                || name.equals("reference handler") || name.equals("finalizer")
                || name.equals("common-cleaner") || name.equals("notification thread")
                || name.equals("monitor ctrl-break") || name.startsWith("jdwp ")
                || name.contains("transport listener")) {
            return "JVM service/signal/cleanup thread";
        }
        return null;
    }

    private DebuggerFreezeReport report(DebuggerFreezeReport.Operation operation,
            List<DebuggerFreezeReport.Entry> entries) {
        return new DebuggerFreezeReport(operation, active, generation,
                System.currentTimeMillis(), owned.size(), entries);
    }

    private static DebuggerFreezeReport.Entry entry(RemoteJvmtiThread thread,
            DebuggerFreezeReport.Action action, String detail) {
        return new DebuggerFreezeReport.Entry(thread.name(), thread.capturedState(),
                thread.stateSummary(), thread.daemon(), action, detail);
    }

    private static boolean alreadyReleased(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return lower.contains("not paused") || lower.contains("no longer paused")
                || lower.contains("not alive") || lower.contains("thread_not_alive")
                || lower.contains("invalid object") || lower.contains("not found");
    }

    private static boolean nonDebuggable(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return lower.contains("no_more_frames") || lower.contains("no more frames")
                || lower.contains("has no java frame") || lower.contains("thread_not_alive")
                || lower.contains("thread not alive");
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    @Override
    public synchronized void close() {
        try { restore(); } catch (RuntimeException ignored) { }
        for (OwnedThread record : owned.values()) record.close();
        owned.clear();
        active = false;
    }

    private static final class OwnedThread {
        private final RemoteJvmtiThread thread;
        private final int originalState;
        private final String originalStateSummary;

        private OwnedThread(RemoteJvmtiThread thread, int originalState,
                String originalStateSummary) {
            this.thread = thread;
            this.originalState = originalState;
            this.originalStateSummary = originalStateSummary;
        }

        private DebuggerFreezeReport.Entry entry(DebuggerFreezeReport.Action action, String detail) {
            return new DebuggerFreezeReport.Entry(thread.name(), originalState,
                    originalStateSummary, thread.daemon(), action, detail);
        }

        private void close() { thread.close(); }
    }
}
