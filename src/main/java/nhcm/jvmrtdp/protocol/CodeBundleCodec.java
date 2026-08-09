package nhcm.jvmrtdp.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Compact, bounded transport for a group of JVM class files. */
public class CodeBundleCodec {
    private static final int MAGIC = 0x4A434231; // JCB1
    private static final int MAX_CLASSES = 10_000;
    private static final int MAX_CLASS_BYTES = 16 * 1024 * 1024;

    private CodeBundleCodec() {
    }

    public static String encode(Map<String, byte[]> classes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encodeBytes(classes));
    }

    public static byte[] encodeBytes(Map<String, byte[]> classes) {
        if (classes == null || classes.isEmpty() || classes.size() > MAX_CLASSES) {
            throw new IllegalArgumentException("Class bundle size must be between 1 and " + MAX_CLASSES);
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC);
            output.writeInt(classes.size());
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                byte[] name = requireName(entry.getKey()).getBytes(StandardCharsets.UTF_8);
                byte[] classBytes = requireBytes(entry.getValue());
                output.writeInt(name.length);
                output.write(name);
                output.writeInt(classBytes.length);
                output.write(classBytes);
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static Map<String, byte[]> decode(String encoded) {
        try {
            return decodeBytes(Base64.getUrlDecoder().decode(encoded));
        } catch (IllegalArgumentException exception) {
            throw exception;
        }
    }

    public static Map<String, byte[]> decodeBytes(byte[] payload) {
        try {
            if (payload == null) throw new IllegalArgumentException("Class bundle must not be null");
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            if (input.readInt() != MAGIC) throw new IllegalArgumentException("Invalid class bundle magic");
            int count = input.readInt();
            if (count < 1 || count > MAX_CLASSES) throw new IllegalArgumentException("Invalid class count: " + count);
            Map<String, byte[]> classes = new LinkedHashMap<String, byte[]>();
            for (int index = 0; index < count; index++) {
                int nameLength = input.readInt();
                if (nameLength < 1 || nameLength > 65_535) throw new IllegalArgumentException("Invalid class name length");
                byte[] name = new byte[nameLength];
                input.readFully(name);
                int byteCount = input.readInt();
                if (byteCount < 1 || byteCount > MAX_CLASS_BYTES) throw new IllegalArgumentException("Invalid class byte count");
                byte[] classBytes = new byte[byteCount];
                input.readFully(classBytes);
                String className = requireName(new String(name, StandardCharsets.UTF_8));
                if (classes.put(className, classBytes) != null) throw new IllegalArgumentException("Duplicate class: " + className);
            }
            if (input.read() != -1) throw new IllegalArgumentException("Trailing class bundle data");
            return Collections.unmodifiableMap(classes);
        } catch (IOException | IllegalArgumentException exception) {
            if (exception instanceof IllegalArgumentException) throw (IllegalArgumentException) exception;
            throw new IllegalArgumentException("Invalid class bundle", exception);
        }
    }

    private static String requireName(String name) {
        if (name == null || name.trim().isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid binary class name: " + name);
        }
        return name;
    }

    private static byte[] requireBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 4 || bytes.length > MAX_CLASS_BYTES
                || bytes[0] != (byte) 0xCA || bytes[1] != (byte) 0xFE
                || bytes[2] != (byte) 0xBA || bytes[3] != (byte) 0xBE) {
            throw new IllegalArgumentException("Invalid JVM class bytes");
        }
        return bytes;
    }
}
