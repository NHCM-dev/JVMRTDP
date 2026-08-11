package nhcm.jvmrtdp.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmProcessInfoTest {
    @Test
    void exposesAStableProcessSnapshot() {
        Instant start = Instant.now().minusSeconds(2);
        JvmProcessInfo process = new JvmProcessInfo(
                42, "application.jar", "java.exe", "Application", "x86_64", start, true);

        assertEquals(42, process.pid());
        assertEquals("application.jar", process.displayName());
        assertEquals("java.exe", process.executableName());
        assertEquals("Application", process.windowTitle());
        assertEquals("x86_64", process.architecture());
        assertEquals(start, process.startedAt().get());
        assertTrue(process.uptime().compareTo(Duration.ofSeconds(1)) >= 0);
        assertTrue(process.isAlive());
    }

    @Test
    void missingStartTimeProducesZeroUptime() {
        JvmProcessInfo process = new JvmProcessInfo(7, null, null, null, null, null, false);

        assertFalse(process.startedAt().isPresent());
        assertEquals(Duration.ZERO, process.uptime());
        assertFalse(process.isAlive());
    }
}
