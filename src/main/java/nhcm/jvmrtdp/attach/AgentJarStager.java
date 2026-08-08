package nhcm.jvmrtdp.attach;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Copies the agent JAR outside the project before injection.
 *
 * <p>URLClassLoader keeps its JAR open on Windows while agent classes are alive. Loading an
 * immutable, content-addressed staging copy prevents the target process from locking the user's
 * original JVMRTDP.jar.</p>
 */
public class AgentJarStager {
    private static final String DIRECTORY_NAME = "jvmrtdp-agent-jars";

    private AgentJarStager() {
    }

    public static Path stage(Path sourceJar) {
        Path source = sourceJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Agent JAR does not exist: " + source);
        }
        try {
            byte[] content = Files.readAllBytes(source);
            String hash = sha256(content);
            Path directory = Paths.get(System.getProperty("java.io.tmpdir"), DIRECTORY_NAME, hash);
            Path staged = directory.resolve("JVMRTDP-agent.jar");
            Files.createDirectories(directory);
            if (isExpected(staged, content)) return staged;

            Path temporary = directory.resolve("JVMRTDP-agent-" + UUID.randomUUID() + ".tmp");
            Files.write(temporary, content);
            try {
                moveIntoPlace(temporary, staged);
            } catch (IOException race) {
                Files.deleteIfExists(temporary);
                if (!isExpected(staged, content)) throw race;
            }
            if (!isExpected(staged, content)) {
                throw new IOException("Staged agent JAR failed content verification: " + staged);
            }
            return staged;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot stage agent JAR: " + source, exception);
        }
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static boolean isExpected(Path file, byte[] expected) throws IOException {
        return Files.isRegularFile(file) && sha256(Files.readAllBytes(file)).equals(sha256(expected));
    }

    private static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
