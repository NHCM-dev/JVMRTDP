package nhcm.jvmrtdp.protocol;

public class Protocol {
    public static final int MAGIC = 0x4A524450; // JRDP
    public static final short VERSION = 1;
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    private Protocol() {
    }
}
