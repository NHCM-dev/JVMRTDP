package nhcm.jvmrtdp.controllerside.debug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable result of freezing, refreshing, restoring, or inspecting an analysis freeze. */
public final class DebuggerFreezeReport {
    public enum Operation { FREEZE, REFRESH, RESTORE, STATUS }
    public enum Action { FROZEN, RESTORED, PRESERVED, EXCLUDED, FAILED, ACTIVE }

    private final Operation operation;
    private final boolean active;
    private final long generation;
    private final long capturedAtMillis;
    private final int ownedThreadCount;
    private final List<Entry> entries;

    public DebuggerFreezeReport(Operation operation, boolean active, long generation,
            long capturedAtMillis, int ownedThreadCount, List<Entry> entries) {
        this.operation = operation;
        this.active = active;
        this.generation = generation;
        this.capturedAtMillis = capturedAtMillis;
        this.ownedThreadCount = ownedThreadCount;
        this.entries = Collections.unmodifiableList(new ArrayList<Entry>(entries));
    }

    public Operation operation() { return operation; }
    public boolean active() { return active; }
    public long generation() { return generation; }
    public long capturedAtMillis() { return capturedAtMillis; }
    public int ownedThreadCount() { return ownedThreadCount; }
    public List<Entry> entries() { return entries; }

    public int count(Action wanted) {
        int count = 0;
        for (Entry entry : entries) if (entry.action() == wanted) count++;
        return count;
    }

    public String summary() {
        return operation.name().toLowerCase() + ": active=" + active
                + " owned=" + ownedThreadCount
                + " frozen=" + count(Action.FROZEN)
                + " restored=" + count(Action.RESTORED)
                + " preserved=" + count(Action.PRESERVED)
                + " excluded=" + count(Action.EXCLUDED)
                + " failed=" + count(Action.FAILED);
    }

    public static final class Entry {
        private final String threadName;
        private final int originalState;
        private final String originalStateSummary;
        private final boolean daemon;
        private final Action action;
        private final String detail;

        public Entry(String threadName, int originalState, String originalStateSummary,
                boolean daemon, Action action, String detail) {
            this.threadName = threadName == null ? "" : threadName;
            this.originalState = originalState;
            this.originalStateSummary = originalStateSummary == null ? "" : originalStateSummary;
            this.daemon = daemon;
            this.action = action;
            this.detail = detail == null ? "" : detail;
        }

        public String threadName() { return threadName; }
        public int originalState() { return originalState; }
        public String originalStateSummary() { return originalStateSummary; }
        public boolean daemon() { return daemon; }
        public Action action() { return action; }
        public String detail() { return detail; }
    }
}
