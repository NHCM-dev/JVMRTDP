package nhcm.jvmrtdp.handles.jvm;

import nhcm.jvmrtdp.handles.RemoteHandle;
import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.command.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

public class RemoteJVMTIEnv extends RemoteHandle {
    public RemoteJVMTIEnv(ServerHandle server, long remoteId) {
        super(server, remoteId);
    }

    public byte[] getClassBytes(String className) {
        String encoded = executeForOutput(CommandLine.of("jvmti", "bytes", className));
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Target returned invalid class bytes", exception);
        }
    }

    /** Always materializes the remote class bytes as a controller-side file. */
    public Path dumpClass(String className, Path outputFile) throws IOException {
        if (outputFile == null) {
            throw new IllegalArgumentException("Output file must not be null");
        }
        Path absolute = outputFile.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(absolute, getClassBytes(className));
        return absolute;
    }
}
