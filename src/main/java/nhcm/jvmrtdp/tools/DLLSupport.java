package nhcm.jvmrtdp.tools;

import nhcm.jvmrtdp.throwble.DLLException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DLLSupport {
    public static final String INJECTOR_RESOURCE = "/natives/windows-x86_64/jvmrtdp-injector.dll";
    public static final String AGENT_RESOURCE = "/natives/windows-x86_64/jvmrtdp-agent.dll";

    private static final Map<String, Path> LOADED_RESOURCES = new HashMap<String, Path>();
    private static final String INSTANCE_ID = UUID.randomUUID().toString();

    private DLLSupport() {
    }

    public static synchronized Path loadDllFromJar(String resourcePath) {
        Path loaded = LOADED_RESOURCES.get(resourcePath);
        if (loaded != null) {
            return loaded;
        }
        try (InputStream in = DLLSupport.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }

            byte[] bytes = readAll(in);
            String hash = sha256(bytes).substring(0, 24);
            String fileName = Paths.get(resourcePath).getFileName().toString();
            Path nativeDirectory = Paths.get(
                    System.getProperty("java.io.tmpdir"), "jvmrtdp-native", hash, INSTANCE_ID);
            Path dll = nativeDirectory.resolve(fileName);
            Files.createDirectories(nativeDirectory);

            if (!Files.exists(dll) || !MessageDigest.isEqual(Files.readAllBytes(dll), bytes)) {
                Path temporary = nativeDirectory.resolve(fileName + ".tmp");
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, dll, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, dll, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            System.load(dll.toAbsolutePath().toString());
            LOADED_RESOURCES.put(resourcePath, dll);
            return dll;
        } catch (IOException | UnsatisfiedLinkError exception) {
            throw new DLLException(resourcePath, exception.toString());
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
