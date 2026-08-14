package org.benf.cfr.reader.util;

/**
 * very simple enum to help switching on an XOR style decision.
 */
public enum Troolean {
    NEITHER,
    TRUE,
    FALSE;

    public static Troolean get(Boolean a) {
        if (a == null) return NEITHER;
        return a ? TRUE : FALSE;
    }

    public Troolean negate() {
        switch (this) {
            case TRUE:
                return FALSE;
            case FALSE:
                return TRUE;
            default:
                return NEITHER;
        }
    }

    public boolean boolValue(boolean ifNeither) {
        switch (this) {
            case TRUE:
                return true;
            case FALSE:
                return false;
            default:
                return ifNeither;
        }
    }
}
