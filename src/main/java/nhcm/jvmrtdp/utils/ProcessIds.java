package nhcm.jvmrtdp.utils;

import java.lang.management.ManagementFactory;

public class ProcessIds {
    private ProcessIds() {
    }

    public static long current() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        int separator = runtimeName.indexOf('@');
        String pid = separator < 0 ? runtimeName : runtimeName.substring(0, separator);
        try {
            return Long.parseLong(pid);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Cannot determine current JVM process ID: " + runtimeName, exception);
        }
    }
}
