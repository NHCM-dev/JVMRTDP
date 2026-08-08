package nhcm.jvmrtdp;

import nhcm.jvmrtdp.localside.ControllerShell;

public class JVMRuntimeDump {
    private JVMRuntimeDump() {
    }

    public static void main(String[] args) {
        try {
            ControllerShell shell = new ControllerShell(new JVMRTDP(), System.in, System.out, System.err);
            if (args.length == 0) {
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
        System.out.println("  java -jar JVMRTDP.jar list");
        System.out.println("  java -jar JVMRTDP.jar inject <pid>");
    }
}
