package nhcm.jvmrtdp.controllerside.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmClassPathCatalogTest {
    @TempDir Path temporary;

    @Test
    void keepsLoadedAndUnloadedClassesInSeparateViewsAndReadsMembers() throws Exception {
        writeClass("sample.Pending");
        writeClass("sample.AlreadyLoaded");

        JvmClassPathCatalog catalog = JvmClassPathCatalog.discover(
                temporary.toString(), temporary.toString(),
                Collections.singleton("sample.AlreadyLoaded"));

        assertEquals(2, catalog.size());
        assertEquals(1, catalog.unloadedSize());
        assertTrue(catalog.isLoaded("sample.AlreadyLoaded"));
        assertFalse(catalog.isLoaded("sample.Pending"));
        assertEquals(Collections.singletonList("sample"),
                catalog.packageView("").packages());
        assertEquals("sample.Pending", catalog.packageView("sample").classes().get(0).name());

        JvmClassPathCatalog.ClassEntry pending = catalog.find("sample/Pending");
        assertNotNull(pending);
        assertEquals("java.lang.Object", pending.metadata().superName());
        assertTrue(pending.metadata().fields().stream()
                .anyMatch(member -> member.name().equals("value")
                        && member.descriptor().equals("I")));
        assertEquals(1, catalog.searchUnloadedMembers("sample.*", "run",
                JvmClassPathCatalog.MemberKind.METHOD, 10).size());
    }

    private void writeClass(String className) throws Exception {
        String internal = className.replace('.', '/');
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "value", Type.INT_TYPE.getDescriptor(), null, null)
                .visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).visitEnd();
        writer.visitEnd();
        Path output = temporary.resolve(internal + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
