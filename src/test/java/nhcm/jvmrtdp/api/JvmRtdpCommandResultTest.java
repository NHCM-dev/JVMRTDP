package nhcm.jvmrtdp.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmRtdpCommandResultTest {
    @Test
    void successfulResultKeepsSeparatedOutput() {
        JvmRtdpCommandResult result = JvmRtdpCommandResult.success(
                "jvmti phase", true, "LIVE\n", "");

        assertTrue(result.successful());
        assertTrue(result.sessionContinuationRequested());
        assertEquals("LIVE\n", result.standardOutput());
        assertEquals("", result.standardError());
        assertSame(result, result.requireSuccess());
    }

    @Test
    void failedResultCanBeCheckedWithoutParsingText() {
        JvmRtdpCommandResult result = JvmRtdpCommandResult.failure(
                "context class missing.Type", "", "", new IllegalStateException("not found"));

        assertFalse(result.successful());
        assertEquals(IllegalStateException.class.getName(), result.failureType());
        assertEquals("not found", result.failureMessage());
        JvmRtdpCommandException failure = assertThrows(
                JvmRtdpCommandException.class, result::requireSuccess);
        assertSame(result, failure.result());
    }

    @Test
    void commandDiagnosticsAreStructuredFailures() {
        JvmRtdpCommandResult result = JvmRtdpCommandResult.diagnosticFailure(
                "missing", true, "", "Unknown target command\n");

        assertFalse(result.successful());
        assertEquals("command-error", result.failureType());
        assertEquals("Unknown target command", result.failureMessage());
    }
}
