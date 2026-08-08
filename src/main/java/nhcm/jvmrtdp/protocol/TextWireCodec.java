package nhcm.jvmrtdp.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Encodes structured command results without exposing delimiter escaping to commands. */
public class TextWireCodec {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private TextWireCodec() {
    }

    public static String encode(String... values) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index != 0) {
                result.append('.');
            }
            result.append(ENCODER.encodeToString(values[index].getBytes(StandardCharsets.UTF_8)));
        }
        return result.toString();
    }

    public static List<String> decode(String encoded, int expectedParts) {
        String[] parts = encoded.split("\\.", -1);
        if (parts.length != expectedParts) {
            throw new IllegalArgumentException(
                    "Expected " + expectedParts + " wire fields, received " + parts.length);
        }
        List<String> result = new ArrayList<String>(parts.length);
        for (String part : parts) {
            result.add(new String(DECODER.decode(part), StandardCharsets.UTF_8));
        }
        return result;
    }
}
