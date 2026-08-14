package nhcm.jvmrtdp.api.hook;

/**
 * Immutable filter for stopping when a newly observable {@link String} matches. Patterns use
 * {@code *} and {@code ?}. Creator patterns are matched against any Java frame in the allocating
 * thread, and all three components must match one frame. Fast mode uses a constructor-return
 * probe with bootstrap content prefiltering; complete mode adds the JVM-wide allocation event.
 * Use {@code *text*} for contains.
 */
public final class JvmStringAllocationSpec {
    private final String contentPattern;
    private final String creatorClassPattern;
    private final String creatorMethodPattern;
    private final String creatorDescriptorPattern;
    private final boolean caseSensitive;
    private final JvmStringAllocationMode mode;
    private final long maximumHits;
    private final int sampleEvery;

    private JvmStringAllocationSpec(Builder builder) {
        this.contentPattern = pattern(builder.contentPattern);
        this.creatorClassPattern = classPattern(builder.creatorClassPattern);
        this.creatorMethodPattern = pattern(builder.creatorMethodPattern);
        this.creatorDescriptorPattern = pattern(builder.creatorDescriptorPattern);
        this.caseSensitive = builder.caseSensitive;
        this.mode = builder.mode == null ? JvmStringAllocationMode.FAST : builder.mode;
        if (builder.maximumHits < 0L) {
            throw new IllegalArgumentException("maximumHits must not be negative");
        }
        if (builder.sampleEvery < 1) {
            throw new IllegalArgumentException("sampleEvery must be positive");
        }
        this.maximumHits = builder.maximumHits;
        this.sampleEvery = builder.sampleEvery;
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
    public JvmStringAllocationMode mode() { return mode; }
    /** Zero means unlimited. */
    public long maximumHits() { return maximumHits; }
    /** Emits one debugger stop for every Nth matching String. */
    public int sampleEvery() { return sampleEvery; }

    public String summary() {
        return "content=" + contentPattern + ", creator=" + creatorClassPattern + '#'
                + creatorMethodPattern + ' ' + creatorDescriptorPattern
                + ", mode=" + mode.name().toLowerCase(java.util.Locale.ROOT)
                + (caseSensitive ? "" : ", ignore-case")
                + (maximumHits == 0L ? "" : ", max-hits=" + maximumHits)
                + (sampleEvery == 1 ? "" : ", sample-every=" + sampleEvery);
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
        private JvmStringAllocationMode mode = JvmStringAllocationMode.FAST;
        private long maximumHits;
        private int sampleEvery = 1;

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

        public Builder mode(JvmStringAllocationMode value) {
            if (value == null) throw new IllegalArgumentException("mode must not be null");
            this.mode = value;
            return this;
        }

        /** Limits emitted debugger stops; zero keeps the hook active indefinitely. */
        public Builder maximumHits(long value) {
            this.maximumHits = value;
            return this;
        }

        /** Convenience policy for stopping only on the first matching String. */
        public Builder oneShot() { return maximumHits(1L); }

        /** Emits only every Nth content/creator match. */
        public Builder sampleEvery(int value) {
            this.sampleEvery = value;
            return this;
        }

        public JvmStringAllocationSpec build() { return new JvmStringAllocationSpec(this); }
    }
}
