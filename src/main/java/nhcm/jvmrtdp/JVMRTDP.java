package nhcm.jvmrtdp;

import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.tools.JRDInjector;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public class JVMRTDP {
    private final JRDInjector injector;

    public JVMRTDP() {
        this.injector = new JRDInjector();
    }

    public List<JVMProcess> getProcesses() {
        return injector.getProcesses();
    }

    public JVMProcess getProcess(long pid) {
        return injector.getProcess(pid);
    }

    public ServerHandle inject(long pid) {
        return injector.inject(getProcess(pid));
    }

    public ServerHandle inject(long pid, Path agentJar, Duration timeout) {
        return injector.inject(getProcess(pid), agentJar, timeout);
    }

    public JRDInjector injector() {
        return injector;
    }
}
