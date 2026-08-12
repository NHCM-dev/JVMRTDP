package nhcm.jvmrtdp.api;

import nhcm.jvmrtdp.api.jvmti.JvmtiEventType;
import nhcm.jvmrtdp.handles.jvm.RemoteCodeDeployment;
import nhcm.jvmrtdp.handles.jvm.RemoteJVMTIEnv;
import nhcm.jvmrtdp.handles.jvm.RemoteJvmtiCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/** High-level library facade for target-side code deployment, hooks and class instrumentation. */
public final class JvmInstrumentation {
    private final RemoteJVMTIEnv jvmti;

    JvmInstrumentation(RemoteJVMTIEnv jvmti) { this.jvmti = jvmti; }

    public byte[] classBytes(String className) { return jvmti.getClassBytes(className); }

    public void redefine(String className, byte[] classBytes) {
        jvmti.redefineClass(className, classBytes);
    }

    public void redefine(String className, Path classFile) throws IOException {
        redefine(className, Files.readAllBytes(classFile.toAbsolutePath().normalize()));
    }

    /** Reads the live class file, transforms it in the controller, and redefines it atomically. */
    public byte[] transformAndRedefine(String className, UnaryOperator<byte[]> transformer) {
        if (transformer == null) throw new IllegalArgumentException("transformer must not be null");
        byte[] current = classBytes(className);
        byte[] replacement = transformer.apply(current.clone());
        if (replacement == null) throw new IllegalArgumentException("transformer returned null");
        redefine(className, replacement);
        return replacement.clone();
    }

    public void retransform(String className) { jvmti.retransformClass(className); }

    public RemoteCodeDeployment deployClasses(String name, Map<String, byte[]> classes,
            String anchorClass, RemoteJVMTIEnv.DefinitionMode mode) {
        return jvmti.deployClasses(name, classes, anchorClass, mode);
    }

    public RemoteCodeDeployment deployClasses(String name, Map<String, byte[]> classes) {
        return jvmti.deployClasses(name, classes);
    }

    public RemoteCodeDeployment deploySource(String name, String binaryClassName, String source)
            throws IOException {
        return jvmti.deploySource(name, binaryClassName, source);
    }

    public RemoteCodeDeployment deploySource(String name, String binaryClassName, String source,
            List<Path> classpath, List<String> compilerOptions, String anchorClass,
            RemoteJVMTIEnv.DefinitionMode mode) throws IOException {
        return jvmti.deploySource(name, binaryClassName, source, classpath,
                compilerOptions, anchorClass, mode);
    }

    public RemoteCodeDeployment deployMethods(String name, String binaryClassName,
            String methodsSource, List<Path> classpath, List<String> compilerOptions,
            String anchorClass, RemoteJVMTIEnv.DefinitionMode mode) throws IOException {
        return jvmti.deployMethods(name, binaryClassName, methodsSource, classpath,
                compilerOptions, anchorClass, mode);
    }

    public RemoteCodeDeployment deployMethods(String name, String binaryClassName,
            String methodsSource) throws IOException {
        return jvmti.deployMethods(name, binaryClassName, methodsSource,
                java.util.Collections.<Path>emptyList(), java.util.Collections.<String>emptyList(),
                "", RemoteJVMTIEnv.DefinitionMode.CHILD);
    }

    public RemoteCodeDeployment addJar(String name, Path jar, RemoteJVMTIEnv.JarScope scope,
            String anchorClass) throws IOException {
        return jvmti.addJar(name, jar, scope, anchorClass);
    }

    /** Registers a deployed {@code JvmtiEventHandler}. Close the result to remove the hook. */
    public RemoteJvmtiCallback hook(RemoteCodeDeployment deployment, String handlerClass,
            Set<JvmtiEventType> events, boolean synchronous) {
        if (deployment == null) throw new IllegalArgumentException("deployment must not be null");
        return deployment.registerCallback(handlerClass, events, synchronous);
    }

    /** Registers a deployed {@code JvmtiClassFileTransformer}. Retransform to apply it to loaded classes. */
    public RemoteJvmtiCallback transformer(RemoteCodeDeployment deployment,
            String transformerClass, boolean synchronous) {
        if (deployment == null) throw new IllegalArgumentException("deployment must not be null");
        return deployment.registerCallback(transformerClass,
                java.util.Collections.singleton(JvmtiEventType.CLASS_FILE_LOAD_HOOK), synchronous);
    }
}
