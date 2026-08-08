package nhcm.jvmrtdp.controllerside;

import nhcm.jvmrtdp.BuildInfo;

import java.io.PrintStream;
import java.util.Objects;

/** Renders the controller banner without introducing terminal-specific dependencies. */
public final class StartupBanner {
    private static final String[] ART = {
            "     _ __     ____  __ ____ _____ ____  ____  ",
            "    | |\\ \\   / /  \\/  |  _ \\_   _|  _ \\|  _ \\ ",
            " _  | | \\ \\ / /| |\\/| | |_) || | | | | | |_) |",
            "| |_| |  \\ V / | |  | |  _ < | | | |_| |  __/ ",
            " \\___/    \\_/  |_|  |_|_| \\_\\|_| |____/|_|    "
    };

    private StartupBanner() {
    }

    public static void printTo(PrintStream output) {
        Objects.requireNonNull(output, "output");
        for (String line : ART) output.println(line);
        output.println("  " + BuildInfo.displayVersion() + " | Target JVM diagnostics & object control");
        output.println();
    }
}
