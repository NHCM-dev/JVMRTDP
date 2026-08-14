package nhcm.jvmrtdp.api.bytecode;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JvmBytecodeAssemblerTest {
    @Test void assemblesMultipleStatementsAndDescriptors() {
        InsnList values = new JvmBytecodeAssembler().assemble(
                "ALOAD 0 ;; INVOKEVIRTUAL java/lang/String length ()I ;; IRETURN");
        assertEquals(3, values.size());
        assertEquals(Opcodes.ALOAD, values.get(0).getOpcode());
        assertEquals(Opcodes.INVOKEVIRTUAL, values.get(1).getOpcode());
        assertEquals(Opcodes.IRETURN, values.get(2).getOpcode());
    }

    @Test void resolvesLocalLabels() {
        InsnList values = new JvmBytecodeAssembler().assemble(
                "ICONST_0 ;; IFEQ done ;; ICONST_1 ;; IRETURN ;; LABEL done ;; ICONST_2 ;; IRETURN");
        JumpInsnNode jump = (JumpInsnNode) values.get(1);
        assertSame(jump.label, values.get(4));
    }

    @Test void rejectsUnknownJumpTarget() {
        assertThrows(IllegalArgumentException.class,
                () -> new JvmBytecodeAssembler().assemble("GOTO missing"));
    }

    @Test void buildsAtomicMultiOperationPatch() {
        JvmBytecodePatch patch = JvmBytecodePatch.builder("example.Target")
                .insertBefore("run", "()V", 0, "NOP")
                .delete("run", "()V", 4, 9)
                .replaceReturns("run", "()V", "RETURN")
                .build();
        assertEquals(3, patch.operations().size());
    }

    @Test void buildsManualExceptionTableEdits() {
        JvmBytecodePatch patch = JvmBytecodePatch.builder("example.Target")
                .addExceptionHandler("run", "()V", 0, 9, 12,
                        "java.lang.RuntimeException")
                .deleteExceptionHandler("run", "()V", 0)
                .build();

        assertEquals(JvmBytecodePatch.Kind.ADD_EXCEPTION_HANDLER,
                patch.operations().get(0).kind());
        assertEquals("12|java.lang.RuntimeException",
                patch.operations().get(0).assembly());
        assertEquals(JvmBytecodePatch.Kind.DELETE_EXCEPTION_HANDLER,
                patch.operations().get(1).kind());
    }
}
