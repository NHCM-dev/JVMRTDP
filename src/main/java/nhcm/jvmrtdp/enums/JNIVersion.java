package nhcm.jvmrtdp.enums;

public enum JNIVersion
{
    JNI_VERSION_1_1(0x00010001),
    JNI_VERSION_1_2(0x00010002),
    JNI_VERSION_1_4(0x00010004),
    JNI_VERSION_1_6(0x00010006),
    JNI_VERSION_1_8(0x00010008),
    JNI_VERSION_9  (0x00090000),
    JNI_VERSION_10 (0x000a0000),
    JNI_VERSION_19 (0x00130000),
    JNI_VERSION_20 (0x00140000),
    JNI_VERSION_21 (0x00150000);

    private final int code;

    JNIVersion(int code)
    {
        this.code = code;
    }

    public int getCode()
    {
        return this.code;
    }
}
