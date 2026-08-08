package nhcm.jvmrtdp.protocol;

import java.util.List;

public class RemoteObjectDescriptor {
    private final long id;
    private final boolean nullValue;
    private final String className;
    private final String displayValue;

    public RemoteObjectDescriptor(long id, boolean nullValue, String className, String displayValue) {
        if (id <= 0) {
            throw new IllegalArgumentException("Remote object ID must be positive");
        }
        this.id = id;
        this.nullValue = nullValue;
        this.className = className;
        this.displayValue = displayValue;
    }

    public long id() {
        return id;
    }

    public boolean nullValue() {
        return nullValue;
    }

    public String className() {
        return className;
    }

    public String displayValue() {
        return displayValue;
    }

    public String encode() {
        return TextWireCodec.encode(
                Long.toString(id), Boolean.toString(nullValue), className, displayValue);
    }

    public static RemoteObjectDescriptor decode(String encoded) {
        List<String> fields = TextWireCodec.decode(encoded, 4);
        return new RemoteObjectDescriptor(
                Long.parseLong(fields.get(0)),
                Boolean.parseBoolean(fields.get(1)),
                fields.get(2),
                fields.get(3));
    }
}
