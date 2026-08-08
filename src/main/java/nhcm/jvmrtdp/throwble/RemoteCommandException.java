package nhcm.jvmrtdp.throwble;

public class RemoteCommandException extends RuntimeException {
    private final String remoteCode;

    public RemoteCommandException(String remoteCode, String message) {
        super(message);
        this.remoteCode = remoteCode;
    }

    public String remoteCode() {
        return remoteCode;
    }
}
