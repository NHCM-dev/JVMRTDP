/**
 * Transactional ASM-backed class editing. Build one or more
 * {@link nhcm.jvmrtdp.api.bytecode.JvmBytecodePatch} values, stage them with
 * {@link nhcm.jvmrtdp.api.bytecode.JvmBytecodeEditor}, inspect the staged bytes, and flush once
 * the complete class is verifier-valid. Flush recomputes frames/maxima and relocates managed
 * breakpoints.
 */
package nhcm.jvmrtdp.api.bytecode;
