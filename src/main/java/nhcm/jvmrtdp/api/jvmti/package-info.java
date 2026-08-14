/**
 * Typed JVMTI events, debugger stops, bytecode and event breakpoints, field watches, callback
 * handlers, and class-file transformers.
 *
 * <p>Use BCI breakpoints for concrete Java instructions; event breakpoints for method entry,
 * method exit, exceptions, native methods, and abstract declarations; and field watches for
 * reads/writes. {@link nhcm.jvmrtdp.api.jvmti.JvmtiEventHandler} observes without pausing, while
 * debugger registrations suspend the event thread. The practical guide is
 * {@code docs/JAVA-API.md}.</p>
 */
package nhcm.jvmrtdp.api.jvmti;
