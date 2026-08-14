/**
 * Stable entry points for embedding JVMRTDP in a Java application.
 *
 * <p>Start with {@link nhcm.jvmrtdp.api.JvmRtdpClient}, attach a
 * {@link nhcm.jvmrtdp.api.JvmRtdpSession}, and close both with
 * try-with-resources. A session exposes shared context, debugger, instrumentation,
 * named-reference and String-hook services; the CLI and TUI use the same services.</p>
 *
 * @see nhcm.jvmrtdp.api.reference.JvmReferenceManager
 * @see nhcm.jvmrtdp.api.hook.JvmStringHookManager
 */
package nhcm.jvmrtdp.api;
