package nhcm.jvmrtdp.agent.command.builtin;

import nhcm.jvmrtdp.agent.command.RemoteCommand;
import nhcm.jvmrtdp.handles.JRDHandle;
import nhcm.jvmrtdp.protocol.CodeBundleCodec;
import nhcm.jvmrtdp.protocol.CommandReply;
import nhcm.jvmrtdp.remoteside.TargetCodeService;

import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Internal deployment/execution transport used by the controller-side code API. */
public class CodeCommand implements RemoteCommand {
    @Override
    public String name() {
        return "code";
    }

    @Override
    public String usage() {
        return "code <upload.begin|upload.chunk|upload.abort|deploy.upload|jar.upload|deploy|jar|"
                + "execute|deployments|close|callback.register|callback.unregister|"
                + "callback.list|callback.stats> ...";
    }

    @Override
    public String description() {
        return "Deploys compiled Java/JAR code and manages target-side JVMTI callbacks.";
    }

    @Override
    public CommandReply execute(JRDHandle handle, List<String> arguments) {
        if (arguments.isEmpty()) return invalid();
        TargetCodeService code = handle.targetCode();
        String operation = arguments.get(0).toLowerCase(Locale.ROOT);
        if ("upload.begin".equals(operation) && arguments.size() == 3) {
            return success(code.beginUpload(Long.parseLong(arguments.get(1)), arguments.get(2)));
        }
        if ("upload.chunk".equals(operation) && arguments.size() == 4) {
            return success(Long.toString(code.appendUpload(arguments.get(1), Integer.parseInt(arguments.get(2)),
                    Base64.getUrlDecoder().decode(arguments.get(3)))));
        }
        if ("upload.abort".equals(operation) && arguments.size() == 2) {
            return success(Boolean.toString(code.abortUpload(arguments.get(1))));
        }
        if ("deploy.upload".equals(operation) && arguments.size() == 5) {
            TargetCodeService.DefinitionMode mode = TargetCodeService.DefinitionMode.valueOf(
                    arguments.get(2).toUpperCase(Locale.ROOT));
            return success(code.deployUpload(arguments.get(1), arguments.get(4), optional(arguments.get(3)), mode));
        }
        if ("jar.upload".equals(operation) && arguments.size() == 5) {
            TargetCodeService.JarScope scope = TargetCodeService.JarScope.valueOf(
                    arguments.get(2).toUpperCase(Locale.ROOT));
            return success(code.addJarUpload(arguments.get(1), arguments.get(4), optional(arguments.get(3)), scope));
        }
        if ("deploy".equals(operation) && arguments.size() == 5) {
            TargetCodeService.DefinitionMode mode = TargetCodeService.DefinitionMode.valueOf(
                    arguments.get(2).toUpperCase(Locale.ROOT));
            return success(code.deploy(arguments.get(1), CodeBundleCodec.decode(arguments.get(4)),
                    optional(arguments.get(3)), mode));
        }
        if ("jar".equals(operation) && arguments.size() == 5) {
            TargetCodeService.JarScope scope = TargetCodeService.JarScope.valueOf(
                    arguments.get(2).toUpperCase(Locale.ROOT));
            byte[] bytes = Base64.getUrlDecoder().decode(arguments.get(4));
            return success(code.addJar(arguments.get(1), bytes, optional(arguments.get(3)), scope));
        }
        if ("execute".equals(operation) && arguments.size() >= 6) {
            return success(code.execute(arguments.get(1), arguments.get(2), arguments.get(3),
                    arguments.get(4), id(arguments.get(5)), ids(arguments, 6)).encode());
        }
        if ("deployments".equals(operation) && arguments.size() == 1) {
            return success(join(code.deployments()));
        }
        if ("close".equals(operation) && arguments.size() == 2) {
            return success(Boolean.toString(code.closeDeployment(arguments.get(1))));
        }
        if ("callback.register".equals(operation) && arguments.size() == 5) {
            return success(code.registerCallback(arguments.get(1), arguments.get(2),
                    optional(arguments.get(3)), arguments.get(4)));
        }
        if ("callback.unregister".equals(operation) && arguments.size() == 2) {
            return success(Boolean.toString(code.unregisterCallback(arguments.get(1))));
        }
        if ("callback.list".equals(operation) && arguments.size() == 1) {
            return success(join(code.callbacks()));
        }
        if ("callback.stats".equals(operation) && arguments.size() == 1) {
            return success(code.callbackStatistics());
        }
        return invalid();
    }

    private CommandReply invalid() {
        return new CommandReply(false, "Usage: " + usage());
    }

    private static CommandReply success(String output) {
        return new CommandReply(true, output);
    }

    private static String optional(String value) {
        return "-".equals(value) ? "" : value;
    }

    private static long id(String value) {
        long result = Long.parseLong(value);
        if (result < 0) throw new IllegalArgumentException("Object ID must not be negative: " + value);
        return result;
    }

    private static long[] ids(List<String> arguments, int start) {
        long[] result = new long[arguments.size() - start];
        for (int index = start; index < arguments.size(); index++) result[index - start] = id(arguments.get(index));
        return result;
    }

    private static String join(List<String> rows) {
        return String.join(System.lineSeparator(), rows);
    }
}
