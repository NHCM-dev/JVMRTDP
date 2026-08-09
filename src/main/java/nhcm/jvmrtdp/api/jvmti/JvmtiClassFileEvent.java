package nhcm.jvmrtdp.api.jvmti;

import java.security.ProtectionDomain;

/** Class-load parameters supplied to a bytecode transformer. */
public class JvmtiClassFileEvent {
    private ClassLoader loader;
    private final String className;
    private Class<?> classBeingRedefined;
    private final ProtectionDomain protectionDomain;
    private final byte[] classBytes;

    public JvmtiClassFileEvent(ClassLoader loader, String className,
            Class<?> classBeingRedefined, byte[] classBytes) {
        this(loader, className, classBeingRedefined, null, classBytes);
    }

    public JvmtiClassFileEvent(ClassLoader loader, String className,
            Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classBytes) {
        this.loader = loader;
        this.className = className;
        this.classBeingRedefined = classBeingRedefined;
        this.protectionDomain = protectionDomain;
        this.classBytes = classBytes.clone();
    }

    public ClassLoader loader() { return loader; }
    public String className() { return className; }
    public Class<?> classBeingRedefined() { return classBeingRedefined; }
    public ProtectionDomain protectionDomain() { return protectionDomain; }
    public byte[] classBytes() { return classBytes.clone(); }
}
