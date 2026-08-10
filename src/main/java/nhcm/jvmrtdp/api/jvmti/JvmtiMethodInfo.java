package nhcm.jvmrtdp.api.jvmti;

/** Method metadata returned by JVMTI method inspection functions. */
public final class JvmtiMethodInfo {
    private final String className, name, descriptor, genericSignature;
    private final int modifiers, maxLocals, argumentSize;
    private final long startLocation, endLocation;
    private final boolean nativeMethod, synthetic, obsolete;

    public JvmtiMethodInfo(String className, String name, String descriptor, String genericSignature,
            int modifiers, int maxLocals, int argumentSize, long startLocation, long endLocation,
            boolean nativeMethod, boolean synthetic, boolean obsolete) {
        this.className = className; this.name = name; this.descriptor = descriptor;
        this.genericSignature = genericSignature; this.modifiers = modifiers;
        this.maxLocals = maxLocals; this.argumentSize = argumentSize;
        this.startLocation = startLocation; this.endLocation = endLocation;
        this.nativeMethod = nativeMethod; this.synthetic = synthetic; this.obsolete = obsolete;
    }

    public String className() { return className; }
    public String name() { return name; }
    public String descriptor() { return descriptor; }
    public String genericSignature() { return genericSignature; }
    public int modifiers() { return modifiers; }
    public int maxLocals() { return maxLocals; }
    public int argumentSize() { return argumentSize; }
    public long startLocation() { return startLocation; }
    public long endLocation() { return endLocation; }
    public boolean nativeMethod() { return nativeMethod; }
    public boolean synthetic() { return synthetic; }
    public boolean obsolete() { return obsolete; }

    @Override public String toString() {
        return "JvmtiMethodInfo[" + className + '.' + name + descriptor + ", modifiers=0x"
                + Integer.toHexString(modifiers) + ", maxLocals=" + maxLocals
                + ", argumentSize=" + argumentSize + ", location=" + startLocation + ".."
                + endLocation + ", native=" + nativeMethod + ", synthetic=" + synthetic
                + ", obsolete=" + obsolete + ']';
    }
}
