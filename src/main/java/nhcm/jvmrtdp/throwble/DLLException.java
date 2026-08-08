package nhcm.jvmrtdp.throwble;

public class DLLException extends RuntimeException
{
    public DLLException(String resourcePath, String reason)
    {
        super("JVMRTDP Failed to load DLL: \"" + resourcePath + "\", Reason: " + reason);
    }
}
