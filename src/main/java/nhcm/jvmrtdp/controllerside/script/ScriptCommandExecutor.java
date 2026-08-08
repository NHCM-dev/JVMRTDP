package nhcm.jvmrtdp.controllerside.script;

import nhcm.jvmrtdp.controllerside.TargetSession;

/** Lets a flow script execute the same context command language as the interactive prompt. */
public interface ScriptCommandExecutor {
    boolean execute(TargetSession session, String command) throws Exception;
}
