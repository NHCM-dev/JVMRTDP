package nhcm.jvmrtdp.nativebridge;

import nhcm.jvmrtdp.tools.DLLSupport;

import java.nio.file.Path;
import java.time.Duration;

public class InjectorNative {
    // Kept alongside the native registration table so binding changes remain explicit.
    public static final String BINDING_CLASS = "nhcm/jvmrtdp/nativebridge/InjectorNative";

    private static volatile InjectorNative instance;

    private InjectorNative() {
    }

    public static InjectorNative load() {
        InjectorNative current = instance;
        if (current != null) {
            return current;
        }
        synchronized (InjectorNative.class) {
            if (instance == null) {
                DLLSupport.loadDllFromJar(DLLSupport.INJECTOR_RESOURCE);
                instance = new InjectorNative();
            }
            return instance;
        }
    }

    public long currentProcessId() {
        return nativeCurrentProcessId();
    }

    public boolean isProcessAlive(long pid) {
        return nativeIsProcessAlive(pid);
    }

    public String processArchitecture(long pid) {
        return nativeProcessArchitecture(pid);
    }

    public long[] listJvmProcessIds() {
        return nativeListJvmProcessIds();
    }

    public String processDisplayName(long pid) {
        return nativeProcessDisplayName(pid);
    }

    public void inject(long pid, Path injectorDll, Path agentJar, String options, Duration timeout) {
        nativeInject(
                pid,
                injectorDll.toAbsolutePath().normalize().toString(),
                agentJar.toAbsolutePath().normalize().toString(),
                options,
                timeout.toMillis());
    }

    private static native long nativeCurrentProcessId();

    private static native long[] nativeListJvmProcessIds();

    private static native boolean nativeIsProcessAlive(long pid);

    private static native String nativeProcessArchitecture(long pid);

    private static native String nativeProcessDisplayName(long pid);

    private static native void nativeInject(
            long pid, String injectorDll, String agentJar, String options, long timeoutMillis);
}
