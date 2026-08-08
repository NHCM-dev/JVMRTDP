package nhcm.jvmrtdp.enums;

public enum JVMTIVersion
{
    JVMTI_VERSION_1(0x30010000),
    JVMTI_VERSION_1_0(0x30010000),
    JVMTI_VERSION_1_1(0x30010100),
    JVMTI_VERSION_1_2(0x30010200),
    JVMTI_VERSION_9(0x30090000),
    JVMTI_VERSION_11 (0x300B0000),
    JVMTI_VERSION_19 (0x30130000),
    JVMTI_VERSION_21 (0x30150000);

    private final int code;

    JVMTIVersion(int code)
    {
        this.code = code;
    }

    public int getCode()
    {
        return this.code;
    }
}
