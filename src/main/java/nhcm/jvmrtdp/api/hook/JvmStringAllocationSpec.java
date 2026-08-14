package nhcm.jvmrtdp.api.hook;

/**
 * Immutable filter for stopping when a newly allocated {@link String} has finished becoming
 * observable. Patterns use {@code *} and {@code ?}. Creator patterns are matched against any
 * Java frame in the allocating thread, and all three creator components must match the same
 * frame. Use {@code *text*} for a contains match.
 */
public final class JvmStringAllocationSpec {
    private final String contentPattern;
    private final String creatorClassPattern;
    private final String creatorMethodPattern;
    private final String creatorDescriptorPattern;
    private final boolean caseSensitive;

    private JvmStringAllocationSpec(Builder builder) {
        this.contentPattern = pattern(builder.contentPattern);
        this.creatorClassPattern = classPattern(builder.creatorClassPattern);
        this.creatorMethodPattern = pattern(builder.creatorMethodPattern);
        this.creatorDescriptorPattern = pattern(builder.creatorDescriptorPattern);
        this.caseSensitive = builder.caseSensitive;
    }

    public static Builder builder() { return new Builder(); }

    /** Matches every newly observable String. */
    public static JvmStringAllocationSpec any() { return builder().build(); }

    /** Convenience filter equivalent to a glob of {@code *fragment*}. */
    public static JvmStringAllocationSpec containing(String fragment) {
        if (fragment == null) throw new IllegalArgumentException("fragment must not be null");
        return builder().contentGlob("*" + fragment + "*").build();
    }

    public String contentPattern() { return contentPattern; }
    public String creatorClassPattern() { return creatorClassPattern; }
    public String creatorMethodPattern() { return creatorMethodPattern; }
    public String creatorDescriptorPattern() { return creatorDescriptorPattern; }
    public boolean caseSensitive() { return caseSensitive; }

    public String summary() {
        return "content=" + contentPattern + ", creator=" + creatorClassPattern + '#'
                + creatorMethodPattern + creatorDescriptorPattern
                + (caseSensitive ? "" : ", ignore-case");
    }

    private static String pattern(String value) {
        return value == null || value.isEmpty() ? "*" : value;
    }

    private static String classPattern(String value) {
        return pattern(value).replace('/', '.');
    }

    public static final class Builder {
        private String contentPattern = "*";
        private String creatorClassPattern = "*";
        private String creatorMethodPattern = "*";
        private String creatorDescriptorPattern = "*";
        private boolean caseSensitive = true;

        public Builder contentGlob(String pattern) {
            this.contentPattern = JvmStringAllocationSpec.pattern(pattern);
            return this;
        }

        public Builder createdFrom(String classPattern, String methodPattern,
                String descriptorPattern) {
            this.creatorClassPattern = JvmStringAllocationSpec.classPattern(classPattern);
            this.creatorMethodPattern = JvmStringAllocationSpec.pattern(methodPattern);
            this.creatorDescriptorPattern = JvmStringAllocationSpec.pattern(descriptorPattern);
            return this;
        }

        public Builder caseSensitive(boolean value) {
            this.caseSensitive = value;
            return this;
        }

        public JvmStringAllocationSpec build() { return new JvmStringAllocationSpec(this); }
    }
}
