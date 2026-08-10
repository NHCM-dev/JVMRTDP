package nhcm.jvmrtdp.api.jvmti;

/** Field metadata returned by JVMTI field inspection functions. */
public final class JvmtiFieldInfo {
    private final String className, name, descriptor, genericSignature, declaringClass;
    private final int modifiers;
    private final boolean synthetic;

    public JvmtiFieldInfo(String className, String name, String descriptor,
            String genericSignature, int modifiers, boolean synthetic, String declaringClass) {
        this.className = className; this.name = name; this.descriptor = descriptor;
        this.genericSignature = genericSignature; this.modifiers = modifiers;
        this.synthetic = synthetic; this.declaringClass = declaringClass;
    }

    public String className() { return className; }
    public String name() { return name; }
    public String descriptor() { return descriptor; }
    public String genericSignature() { return genericSignature; }
    public int modifiers() { return modifiers; }
    public boolean synthetic() { return synthetic; }
    public String declaringClass() { return declaringClass; }
}
