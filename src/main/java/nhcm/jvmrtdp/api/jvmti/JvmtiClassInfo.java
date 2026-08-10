package nhcm.jvmrtdp.api.jvmti;

/** Class metadata returned by JVMTI class inspection functions. */
public final class JvmtiClassInfo {
    private final String className;
    private final String signature;
    private final String genericSignature;
    private final String sourceFile;
    private final int status;
    private final int modifiers;
    private final boolean interfaceType;
    private final boolean arrayType;
    private final boolean modifiable;
    private final int minorVersion;
    private final int majorVersion;

    public JvmtiClassInfo(String className, String signature, String genericSignature,
            String sourceFile, int status, int modifiers, boolean interfaceType,
            boolean arrayType, boolean modifiable, int minorVersion, int majorVersion) {
        this.className = className;
        this.signature = signature;
        this.genericSignature = genericSignature;
        this.sourceFile = sourceFile;
        this.status = status;
        this.modifiers = modifiers;
        this.interfaceType = interfaceType;
        this.arrayType = arrayType;
        this.modifiable = modifiable;
        this.minorVersion = minorVersion;
        this.majorVersion = majorVersion;
    }

    public String className() { return className; }
    public String signature() { return signature; }
    public String genericSignature() { return genericSignature; }
    public String sourceFile() { return sourceFile; }
    public int status() { return status; }
    public int modifiers() { return modifiers; }
    public boolean interfaceType() { return interfaceType; }
    public boolean arrayType() { return arrayType; }
    public boolean modifiable() { return modifiable; }
    public int minorVersion() { return minorVersion; }
    public int majorVersion() { return majorVersion; }

    @Override public String toString() {
        return "JvmtiClassInfo[class=" + className + ", signature=" + signature
                + ", source=" + sourceFile + ", status=0x" + Integer.toHexString(status)
                + ", modifiers=0x" + Integer.toHexString(modifiers) + ", version="
                + majorVersion + '.' + minorVersion + ", interface=" + interfaceType
                + ", array=" + arrayType + ", modifiable=" + modifiable + ']';
    }
}
