package nhcm.jvmrtdp.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachOptionsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultsAutoLocateAgentAndUsePositiveTimeout() {
        AttachOptions options = AttachOptions.defaults();

        assertFalse(options.agentJar().isPresent());
        assertTrue(options.timeout().compareTo(Duration.ZERO) > 0);
    }

    @Test
    void builderNormalizesAgentPathAndKeepsTimeout() {
        Path relative = temporaryDirectory.resolve("nested").resolve("..").resolve("agent.jar");
        AttachOptions options = AttachOptions.builder()
                .agentJar(relative)
                .timeout(Duration.ofSeconds(7))
                .build();

        assertEquals(relative.toAbsolutePath().normalize(), options.agentJar().get());
        assertEquals(Duration.ofSeconds(7), options.timeout());
    }

    @Test
    void timeoutMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> AttachOptions.builder().timeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> AttachOptions.builder().timeout(Duration.ofSeconds(-1)));
    }
}
