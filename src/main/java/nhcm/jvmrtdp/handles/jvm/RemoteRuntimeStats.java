package nhcm.jvmrtdp.handles.jvm;

/** Immutable statistics sampled inside the target JVM. */
public class RemoteRuntimeStats {
    private final int loadedClasses;
    private final long totalLoadedClasses;
    private final int liveThreads;
    private final int retainedHandles;
    private final long usedHeapBytes;
    private final long maxHeapBytes;
    private final long uptimeMillis;
    private final int processors;

    public RemoteRuntimeStats(int loadedClasses, long totalLoadedClasses, int liveThreads,
            int retainedHandles, long usedHeapBytes, long maxHeapBytes, long uptimeMillis, int processors) {
        this.loadedClasses = loadedClasses;
        this.totalLoadedClasses = totalLoadedClasses;
        this.liveThreads = liveThreads;
        this.retainedHandles = retainedHandles;
        this.usedHeapBytes = usedHeapBytes;
        this.maxHeapBytes = maxHeapBytes;
        this.uptimeMillis = uptimeMillis;
        this.processors = processors;
    }

    public int loadedClasses() { return loadedClasses; }
    public long totalLoadedClasses() { return totalLoadedClasses; }
    public int liveThreads() { return liveThreads; }
    public int retainedHandles() { return retainedHandles; }
    public long usedHeapBytes() { return usedHeapBytes; }
    public long maxHeapBytes() { return maxHeapBytes; }
    public long uptimeMillis() { return uptimeMillis; }
    public int processors() { return processors; }
}
