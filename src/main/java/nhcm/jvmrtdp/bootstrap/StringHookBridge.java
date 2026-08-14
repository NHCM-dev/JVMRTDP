package nhcm.jvmrtdp.bootstrap;

/**
 * Minimal bootstrap-defined bridge invoked from instrumented {@code java.lang.String}
 * constructors. The class has no initializer and no dependencies outside {@code java.base}.
 */
public final class StringHookBridge {
    private static volatile boolean active;
    private static volatile String[] contentPatterns = new String[] { "*" };
    private static volatile boolean[] caseSensitive = new boolean[] { true };

    private StringHookBridge() { }

    public static void observed(String value) {
        if (!active) return;
        Thread thread = Thread.currentThread();
        String name = thread == null ? null : thread.getName();
        if (name != null && name.startsWith("jvmrtdp")) return;
        if (!matchesAnyContent(value)) return;
        observed0(value);
    }

    private static native void observed0(String value);

    /** Internal control surface used reflectively by the target-side service. */
    public static void setActive(boolean enabled) {
        active = enabled;
    }

    /** Internal control surface used reflectively by the target-side service. */
    public static void configure(String[] patterns, boolean[] sensitivity, boolean enabled) {
        contentPatterns = patterns == null ? new String[0] : patterns;
        caseSensitive = sensitivity == null ? new boolean[0] : sensitivity;
        active = enabled;
    }

    private static boolean matchesAnyContent(String value) {
        String[] patterns = contentPatterns;
        boolean[] sensitivity = caseSensitive;
        for (int index = 0; index < patterns.length; index++) {
            String pattern = patterns[index];
            if (pattern == null || "*".equals(pattern)) return true;
            boolean exactCase = index >= sensitivity.length || sensitivity[index];
            if (globMatches(pattern, value, exactCase)) return true;
        }
        return false;
    }

    private static boolean globMatches(String pattern, String value, boolean exactCase) {
        int patternIndex = 0;
        int valueIndex = 0;
        int starIndex = -1;
        int retryValueIndex = -1;
        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length()) {
                char patternCharacter = pattern.charAt(patternIndex);
                if (patternCharacter == '?' || charactersEqual(
                        patternCharacter, value.charAt(valueIndex), exactCase)) {
                    patternIndex++;
                    valueIndex++;
                    continue;
                }
                if (patternCharacter == '*') {
                    starIndex = patternIndex++;
                    retryValueIndex = valueIndex;
                    continue;
                }
            }
            if (starIndex < 0) return false;
            patternIndex = starIndex + 1;
            valueIndex = ++retryValueIndex;
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }

    private static boolean charactersEqual(char left, char right, boolean exactCase) {
        if (left == right) return true;
        if (exactCase) return false;
        return foldAscii(left) == foldAscii(right);
    }

    private static char foldAscii(char value) {
        return value >= 'A' && value <= 'Z' ? (char) (value + ('a' - 'A')) : value;
    }
}
