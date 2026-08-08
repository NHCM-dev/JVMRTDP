package nhcm.jvmrtdp.utils;

import java.util.regex.Pattern;

/** Small, predictable full-string glob matcher supporting '*' and '?'. */
public class GlobMatcher {
    private final String glob;
    private final Pattern pattern;

    private GlobMatcher(String glob, boolean caseSensitive) {
        this.glob = glob == null || glob.isEmpty() ? "*" : glob;
        this.pattern = Pattern.compile(toRegex(this.glob), caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
    }

    public static GlobMatcher of(String glob) {
        return new GlobMatcher(glob, true);
    }

    public static GlobMatcher of(String glob, boolean caseSensitive) {
        return new GlobMatcher(glob, caseSensitive);
    }

    public boolean matches(String value) {
        return value != null && pattern.matcher(value).matches();
    }

    public String glob() {
        return glob;
    }

    private static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < glob.length(); index++) {
            char character = glob.charAt(index);
            if (character == '*') regex.append(".*");
            else if (character == '?') regex.append('.');
            else {
                if ("\\.[]{}()+-^$|".indexOf(character) >= 0) regex.append('\\');
                regex.append(character);
            }
        }
        return regex.append('$').toString();
    }
}
