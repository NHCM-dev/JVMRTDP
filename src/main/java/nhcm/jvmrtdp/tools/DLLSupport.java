package nhcm.jvmrtdp.tools;

import nhcm.jvmrtdp.throwble.DLLException;
import nhcm.jvmrtdp.utils.RandomUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class DLLSupport
{
    private static final String InjectDLLPath = "natives/jvmrtdp-inject.dll";
    private static final String RuntimeDLLPath = "natives/jvmrtdp-runtime.dll";

    public static void loadDllFromJar(String resourcePath)
    {
        try (InputStream in = Class.forName(DLLSupport.class.getName()).getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new FileNotFoundException("Resource not found: " + resourcePath);
            }

            Path tempDir = Files.createTempDirectory("JVMRTDP-temp-");
            Path dll = tempDir.resolve(RandomUtils.randomString(4) + ".dll");

            Files.copy(in, dll, StandardCopyOption.REPLACE_EXISTING);

            dll.toFile().deleteOnExit();
            tempDir.toFile().deleteOnExit();

            System.load(dll.toAbsolutePath().toString());
        } catch (ClassNotFoundException | IOException e)
        {
            throw new DLLException(resourcePath, e.toString());
        }
    }
}
