package nhcm.jvmrtdp.api;

import nhcm.jvmrtdp.controllerside.RemoteContext;
import nhcm.jvmrtdp.controllerside.RemoteOperations;
import nhcm.jvmrtdp.controllerside.RemoteWorkspace;
import nhcm.jvmrtdp.controllerside.debug.DebuggerControlService;
import nhcm.jvmrtdp.handles.jvm.RemoteJNIEnv;
import nhcm.jvmrtdp.handles.jvm.RemoteJVMTIEnv;
import nhcm.jvmrtdp.handles.jvm.RemoteJvmtiCallback;
import nhcm.jvmrtdp.api.jvmti.JvmBreakpointCondition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryApiSurfaceTest {
    @Test
    void clientAndSessionHaveDeterministicOwnership() {
        assertTrue(AutoCloseable.class.isAssignableFrom(JvmRtdpClient.class));
        assertTrue(AutoCloseable.class.isAssignableFrom(JvmRtdpSession.class));
    }

    @Test
    void sessionExposesStructuredAndAdvancedApis() throws Exception {
        assertEquals(JvmRtdpCommandResult.class,
                JvmRtdpSession.class.getMethod("execute", String.class).getReturnType());
        assertEquals(RemoteJNIEnv.class, JvmRtdpSession.class.getMethod("jni").getReturnType());
        assertEquals(RemoteJVMTIEnv.class, JvmRtdpSession.class.getMethod("jvmti").getReturnType());
        assertEquals(RemoteOperations.class, JvmRtdpSession.class.getMethod("operations").getReturnType());
        assertEquals(RemoteWorkspace.class, JvmRtdpSession.class.getMethod("workspace").getReturnType());
        assertEquals(RemoteContext.class, JvmRtdpSession.class.getMethod("context").getReturnType());
        assertEquals(DebuggerControlService.class, JvmRtdpSession.class.getMethod("debugger").getReturnType());
        assertEquals(List.class,
                JvmRtdpSession.class.getMethod("executeAgentBatch", List.class).getReturnType());
        assertEquals(AttachOptions.class,
                AttachOptions.builder().timeout(Duration.ofSeconds(1)).build().getClass());
        assertEquals(void.class, RemoteJVMTIEnv.class.getMethod("setBreakpoint",
                String.class, String.class, String.class, long.class,
                JvmBreakpointCondition.class, boolean.class).getReturnType());
        assertEquals(boolean.class,
                RemoteJvmtiCallback.class.getMethod("disable").getReturnType());
        assertEquals(boolean.class,
                RemoteJvmtiCallback.class.getMethod("resetStatistics").getReturnType());
    }

    @Test
    void clientLoadsPackagedNativeBridgeAndDiscoversProcesses() {
        try (JvmRtdpClient client = JvmRtdpClient.open()) {
            assertFalse(client.version().trim().isEmpty());
            assertNotNull(client.processes());
        }
    }
}
