package nhcm.jvmrtdp.controllerside.analysis;

import java.util.Locale;

public enum DecompilerEngine {
    CFR,
    PROCYON;

    public static DecompilerEngine parse(String value) {
        if (value == null || value.trim().isEmpty()) return CFR;
        return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
