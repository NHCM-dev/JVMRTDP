package nhcm.jvmrtdp.api.jvmti;

/** Summary returned by JVMTI GetObjectMonitorUsage. */
public final class JvmtiMonitorUsage {
    private final String ownerClass;
    private final int entryCount, waiterCount, notifyWaiterCount;

    public JvmtiMonitorUsage(String ownerClass, int entryCount, int waiterCount, int notifyWaiterCount) {
        this.ownerClass = ownerClass;
        this.entryCount = entryCount;
        this.waiterCount = waiterCount;
        this.notifyWaiterCount = notifyWaiterCount;
    }

    public String ownerClass() { return ownerClass; }
    public int entryCount() { return entryCount; }
    public int waiterCount() { return waiterCount; }
    public int notifyWaiterCount() { return notifyWaiterCount; }

    @Override public String toString() {
        return "JvmtiMonitorUsage[owner=" + ownerClass + ", entryCount=" + entryCount
                + ", waiters=" + waiterCount + ", notifyWaiters=" + notifyWaiterCount + ']';
    }
}
