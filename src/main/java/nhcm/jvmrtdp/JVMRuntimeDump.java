package nhcm.jvmrtdp;

import nhcm.jvmrtdp.controllerside.ControllerShell;
import nhcm.jvmrtdp.controllerside.tui.ControllerTui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class JVMRuntimeDump {
    private JVMRuntimeDump() {
    }

    public static void main(String[] args) {
        try {
            JVMRTDP controller = new JVMRTDP();
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            ControllerShell shell = new ControllerShell(input, controller, System.out, System.err);
            if (args.length == 0) {
                boolean useCli = Boolean.getBoolean("jvmrtdp.cli") || System.console() == null;
                if (useCli || new ControllerTui(controller, input, System.out, System.err).run()) shell.run();
            } else if ("--tui".equalsIgnoreCase(args[0]) && args.length == 1) {
                if (new ControllerTui(controller, input, System.out, System.err).run()) shell.run();
            } else if ("--cli".equalsIgnoreCase(args[0]) && args.length == 1) {
                shell.run();
            } else if (("list".equalsIgnoreCase(args[0]) || "ps".equalsIgnoreCase(args[0]))
                    && args.length == 1) {
                shell.listProcesses();
            } else if (("inject".equalsIgnoreCase(args[0]) || "attach".equalsIgnoreCase(args[0]))
                    && args.length == 2) {
                shell.attach(parsePid(args[1]));
            } else if (("help".equalsIgnoreCase(args[0]) || "--help".equalsIgnoreCase(args[0]))
                    && args.length == 1) {
                printUsage();
            } else if (("version".equalsIgnoreCase(args[0]) || "--version".equalsIgnoreCase(args[0])
                    || "-V".equals(args[0])) && args.length == 1) {
                System.out.println(BuildInfo.displayVersion());
            } else {
                printUsage();
                System.exit(2);
            }
        } catch (RuntimeException exception) {
            System.err.println("JVMRTDP: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static long parsePid(String value) {
        try {
            long pid = Long.parseLong(value);
            if (pid <= 0) {
                throw new NumberFormatException();
            }
            return pid;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid PID: " + value);
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -jar JVMRTDP.jar");
        System.out.println("  java -jar JVMRTDP.jar --tui");
        System.out.println("  java -jar JVMRTDP.jar --cli");
        System.out.println("  java -jar JVMRTDP.jar list");
        System.out.println("  java -jar JVMRTDP.jar inject <pid>");
        System.out.println("  java -jar JVMRTDP.jar --version");
    }
}
