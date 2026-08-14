package nhcm.jvmrtdp.controllerside.analysis;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.NavigableMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ClassDecompilerMappingTest {
    @Test void mapsAndSlicesBytecodeWithoutALineNumberTable() {
        byte[] bytes = noLineNumberClass();
        ClassDecompiler decompiler = new ClassDecompiler();
        DecompilationResult method = decompiler.decompileMethodResult(
                "example.NoLines", bytes, "choose", "(I)I", DecompilerEngine.CFR);

        NavigableMap<Integer, Integer> mappings = method.lineMappings("choose", "(I)I");
        assertFalse(mappings.isEmpty(), "CFR must map BCI directly even without LineNumberTable");
        assertFalse(method.source().contains("Could not isolate"));

        int from = mappings.firstKey().intValue();
        int to = mappings.lastKey().intValue();
        DecompilationResult range = decompiler.decompileRangeResult(
                "example.NoLines", bytes, "choose", "(I)I", from, to, DecompilerEngine.CFR);
        assertNotNull(range.source());
        assertFalse(range.source().trim().isEmpty());
        assertFalse(range.lineMappings("choose", "(I)I").isEmpty());
    }

    private static byte[] noLineNumberClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "example/NoLines", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "choose", "(I)I", null, null);
        method.visitCode();
        Label zero = new Label();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitJumpInsn(Opcodes.IFEQ, zero);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitLabel(zero);
        method.visitInsn(Opcodes.ICONST_2);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
