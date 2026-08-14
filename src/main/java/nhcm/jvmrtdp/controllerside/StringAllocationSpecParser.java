package nhcm.jvmrtdp.controllerside;

import nhcm.jvmrtdp.api.hook.JvmStringAllocationMode;
import nhcm.jvmrtdp.api.hook.JvmStringAllocationSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared CLI/TUI parser for the compact String-allocation hook syntax. */
public final class StringAllocationSpecParser {
    private StringAllocationSpecParser() { }

    public static JvmStringAllocationSpec parse(List<String> values, int contentIndex) {
        if (values == null || contentIndex < 0 || contentIndex >= values.size()) {
            throw new IllegalArgumentException("A String content glob is required");
        }
        boolean caseSensitive = true;
        JvmStringAllocationMode mode = JvmStringAllocationMode.FAST;
        boolean includeLdc = false;
        long maximumHits = 0L;
        int sampleEvery = 1;
        List<String> creator = new ArrayList<String>(3);
        for (int index = contentIndex + 1; index < values.size(); ++index) {
            String value = values.get(index);
            String option = value.toLowerCase(Locale.ROOT);
            if ("ignore-case".equals(option)) caseSensitive = false;
            else if ("case-sensitive".equals(option)) caseSensitive = true;
            else if ("ldc".equals(option) || "include-ldc".equals(option)) includeLdc = true;
            else if ("no-ldc".equals(option)) includeLdc = false;
            else if ("fast".equals(option) || "mode=fast".equals(option)) {
                mode = JvmStringAllocationMode.FAST;
            } else if ("complete".equals(option) || "mode=complete".equals(option)) {
                mode = JvmStringAllocationMode.COMPLETE;
            } else if ("once".equals(option)) maximumHits = 1L;
            else if (option.startsWith("max=") || option.startsWith("max-hits=")) {
                maximumHits = positiveOrZero(value.substring(value.indexOf('=') + 1), "max-hits");
            } else if (option.startsWith("sample=") || option.startsWith("sample-every=")) {
                long parsed = positive(value.substring(value.indexOf('=') + 1), "sample-every");
                if (parsed > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("sample-every is too large: " + parsed);
                }
                sampleEvery = (int) parsed;
            } else if (creator.size() < 3) creator.add(value);
            else throw new IllegalArgumentException("Unknown String allocation option: " + value);
        }
        return JvmStringAllocationSpec.builder()
                .contentGlob(values.get(contentIndex))
                .createdFrom(creator.size() > 0 ? creator.get(0) : "*",
                        creator.size() > 1 ? creator.get(1) : "*",
                        creator.size() > 2 ? creator.get(2) : "*")
                .caseSensitive(caseSensitive)
                .mode(mode)
                .includeLdc(includeLdc)
                .maximumHits(maximumHits)
                .sampleEvery(sampleEvery)
                .build();
    }

    private static long positiveOrZero(String value, String name) {
        long parsed = number(value, name);
        if (parsed < 0L) throw new IllegalArgumentException(name + " must not be negative");
        return parsed;
    }

    private static long positive(String value, String name) {
        long parsed = number(value, name);
        if (parsed < 1L) throw new IllegalArgumentException(name + " must be positive");
        return parsed;
    }

    private static long number(String value, String name) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value, failure);
        }
    }
}
