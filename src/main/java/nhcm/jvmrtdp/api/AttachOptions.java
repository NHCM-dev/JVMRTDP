package nhcm.jvmrtdp.api;

import nhcm.jvmrtdp.tools.JRDInjector;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** Immutable options for attaching a {@link JvmRtdpSession} to a target JVM. */
public final class AttachOptions {
    private final Path agentJar;
    private final Duration timeout;

    private AttachOptions(Builder builder) {
        this.agentJar = builder.agentJar == null
                ? null : builder.agentJar.toAbsolutePath().normalize();
        this.timeout = positive(builder.timeout, "timeout");
    }

    /** Returns options that auto-locate the current JVMRTDP JAR and use the default timeout. */
    public static AttachOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns an explicit agent JAR, or an empty value when JVMRTDP should locate its own JAR.
     */
    public Optional<Path> agentJar() {
        return Optional.ofNullable(agentJar);
    }

    public Duration timeout() {
        return timeout;
    }

    Path agentJarOrNull() {
        return agentJar;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public static final class Builder {
        private Path agentJar;
        private Duration timeout = JRDInjector.DEFAULT_CONNECT_TIMEOUT;

        private Builder() {
        }

        /** Uses this JAR for target-side agent loading instead of auto-detection. */
        public Builder agentJar(Path value) {
            if (value == null) throw new NullPointerException("agentJar");
            this.agentJar = value;
            return this;
        }

        public Builder timeout(Duration value) {
            this.timeout = positive(value, "timeout");
            return this;
        }

        public AttachOptions build() {
            return new AttachOptions(this);
        }
    }
}
