package nhcm.jvmrtdp.controllerside.analysis;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JavaMethodExtractorTest {
    @Test void extractsClassLevelStaticInitializer() {
        String source = "package example;\n"
                + "public class Demo {\n"
                + "    private static int value;\n"
                + "    static {\n"
                + "        value = 42;\n"
                + "    }\n"
                + "    void run() { if (value > 0) { value--; } }\n"
                + "}\n";

        JavaMethodExtractor.Extraction result = JavaMethodExtractor.extractDetails(
                source, "Demo", "<clinit>", "()V");

        assertEquals("static {\n        value = 42;\n    }", result.source());
        assertEquals(4, result.startLine());
    }

    @Test void extractsStandaloneCfrStaticInitializerDump() {
        String source = "static {\n    System.setProperty(\"demo\", \"true\");\n}\n";

        JavaMethodExtractor.Extraction result = JavaMethodExtractor.extractDetails(
                source, "Demo", "<clinit>", "()V");

        assertEquals("static {\n    System.setProperty(\"demo\", \"true\");\n}", result.source());
        assertEquals(1, result.startLine());
    }

    @Test void cfrDecompilesClinitWithoutFallingBackToTheCompleteClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "example/StaticDemo", null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "value", "I", null, null).visitEnd();
        MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitIntInsn(Opcodes.BIPUSH, 42);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, "example/StaticDemo", "value", "I");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(1, 0);
        clinit.visitEnd();
        writer.visitEnd();

        DecompilationResult result = new ClassDecompiler().decompileMethodResult(
                "example.StaticDemo", writer.toByteArray(), "<clinit>", "()V", DecompilerEngine.CFR);

        assertNotNull(result);
        assertFalse(result.source().contains("Could not isolate"));
        assertTrue(result.source().contains("static"));
        assertTrue(result.source().contains("42"), result.source());
    }

    @Test void fallsBackToUniqueArityWhenDecompilerErasesAnUnresolvableType() {
        String source = "public class Demo {\n"
                + "  public void process(Object value) { System.out.println(value); }\n"
                + "}\n";

        JavaMethodExtractor.Extraction result = JavaMethodExtractor.extractDetails(
                source, "Demo", "process", "(Lmissing/RuntimeType;)V");

        assertNotNull(result);
        assertTrue(result.source().contains("void process"));
    }
}
