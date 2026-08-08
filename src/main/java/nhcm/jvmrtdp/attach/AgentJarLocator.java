package nhcm.jvmrtdp.attach;

import nhcm.jvmrtdp.JVMRTDP;
import nhcm.jvmrtdp.throwble.InjectionException;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AgentJarLocator {
    private AgentJarLocator() {
    }

    public static Path locateCurrentJar() {
        try {
            Path location = java.nio.file.Paths.get(JVMRTDP.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
            if (!Files.isRegularFile(location) || !location.getFileName().toString().endsWith(".jar")) {
                throw new InjectionException(
                        "JVMRTDP is running from classes rather than a JAR; provide the agent JAR path explicitly: "
                                + location);
            }
            return location;
        } catch (URISyntaxException exception) {
            throw new InjectionException("Cannot locate the JVMRTDP agent JAR", exception);
        }
    }
}
