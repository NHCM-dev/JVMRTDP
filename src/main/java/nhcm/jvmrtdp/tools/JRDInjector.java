package nhcm.jvmrtdp.tools;

import nhcm.jvmrtdp.JVMProcess;
import nhcm.jvmrtdp.attach.AgentJarLocator;
import nhcm.jvmrtdp.attach.AgentOptions;
import nhcm.jvmrtdp.attach.AgentJarStager;
import nhcm.jvmrtdp.attach.EndpointFactory;
import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.nativebridge.InjectorNative;
import nhcm.jvmrtdp.throwble.InjectionException;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class JRDInjector {
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(15);

    private final InjectorNative nativeBridge;

    public JRDInjector() {
        this.nativeBridge = InjectorNative.load();
    }

    public List<JVMProcess> getProcesses() {
        long controllerPid = nativeBridge.currentProcessId();
        List<JVMProcess> processes = new ArrayList<JVMProcess>();
        for (long pid : nativeBridge.listJvmProcessIds()) {
            if (pid != controllerPid) {
                processes.add(process(pid));
            }
        }
        Collections.sort(processes, new Comparator<JVMProcess>() {
            @Override
            public int compare(JVMProcess left, JVMProcess right) {
                return Long.compare(left.pid(), right.pid());
            }
        });
        return Collections.unmodifiableList(processes);
    }

    public JVMProcess getProcess(long pid) {
        return process(pid);
    }

    public boolean isProcessAlive(long pid) {
        return nativeBridge.isProcessAlive(pid);
    }

    public String processArchitecture(long pid) {
        return nativeBridge.processArchitecture(pid);
    }

    private JVMProcess process(long pid) {
        long startedAt = nativeBridge.processStartTimeMillis(pid);
        return new JVMProcess(
                pid,
                nativeBridge.processExecutableName(pid),
                nativeBridge.processWindowTitle(pid),
                nativeBridge.processDisplayName(pid),
                startedAt <= 0 ? null : Instant.ofEpochMilli(startedAt),
                this);
    }

    public ServerHandle inject(JVMProcess process) {
        return inject(process, AgentJarLocator.locateCurrentJar(), DEFAULT_CONNECT_TIMEOUT);
    }

    public ServerHandle inject(JVMProcess process, Path agentJar, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        if (!nativeBridge.isProcessAlive(process.pid())) {
            throw new InjectionException("Target process is not running: " + process.pid());
        }
        String architecture = nativeBridge.processArchitecture(process.pid());
        if (!"x86_64".equals(architecture)) {
            throw new InjectionException(
                    "This build contains an x86_64 injector, but target architecture is " + architecture);
        }
        Path normalizedJar = agentJar.toAbsolutePath().normalize();
        if (!normalizedJar.toFile().isFile()) {
            throw new InjectionException("JVMRTDP JAR does not exist: " + normalizedJar);
        }

        AgentOptions options = EndpointFactory.create(process.pid());
        Path injectorDll = DLLSupport.loadDllFromJar(DLLSupport.INJECTOR_RESOURCE);
        Path stagedAgentJar = AgentJarStager.stage(normalizedJar);
        nativeBridge.inject(process.pid(), injectorDll, stagedAgentJar, options.encode(), timeout);
        return ServerHandle.connect(process, options, timeout);
    }
}
