package nhcm.jvmrtdp.api.bytecode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable set of bytecode edits for one loaded class. It may be staged or applied immediately.
 * BCI anchors refer to the class bytes captured before the transaction starts.
 */
public final class JvmBytecodePatch {
    public enum Kind {
        INSERT_BEFORE,
        INSERT_AFTER,
        REPLACE,
        DELETE,
        INSERT_BEFORE_RETURNS,
        REPLACE_RETURNS,
        ADD_EXCEPTION_HANDLER,
        DELETE_EXCEPTION_HANDLER
    }

    public static final class Operation {
        private final Kind kind;
        private final String methodName;
        private final String descriptor;
        private final int fromBci;
        private final int toBci;
        private final String assembly;

        private Operation(Kind kind, String methodName, String descriptor,
                int fromBci, int toBci, String assembly) {
            this.kind = kind;
            this.methodName = required(methodName, "methodName");
            this.descriptor = required(descriptor, "descriptor");
            this.fromBci = fromBci;
            this.toBci = toBci;
            this.assembly = assembly == null ? "" : assembly.trim();
            if (fromBci < -1 || toBci < -1 || (fromBci >= 0 && toBci < fromBci)) {
                throw new IllegalArgumentException("Invalid BCI range " + fromBci + ".." + toBci);
            }
            if (kind != Kind.DELETE && kind != Kind.DELETE_EXCEPTION_HANDLER
                    && this.assembly.isEmpty()) {
                throw new IllegalArgumentException("assembly must not be empty for " + kind);
            }
        }

        public Kind kind() { return kind; }
        public String methodName() { return methodName; }
        public String descriptor() { return descriptor; }
        public int fromBci() { return fromBci; }
        public int toBci() { return toBci; }
        public String assembly() { return assembly; }

        @Override public String toString() {
            return kind + " " + methodName + descriptor
                    + (fromBci < 0 ? "" : " @" + fromBci
                            + (toBci == fromBci ? "" : ".." + toBci));
        }
    }

    private final String className;
    private final List<Operation> operations;

    private JvmBytecodePatch(String className, List<Operation> operations) {
        this.className = required(className, "className").replace('/', '.');
        if (operations.isEmpty()) throw new IllegalArgumentException("At least one bytecode operation is required");
        this.operations = Collections.unmodifiableList(new ArrayList<Operation>(operations));
    }

    public static Builder builder(String className) { return new Builder(className); }

    public String className() { return className; }
    public List<Operation> operations() { return operations; }

    public static final class Builder {
        private final String className;
        private final List<Operation> operations = new ArrayList<Operation>();

        private Builder(String className) { this.className = required(className, "className"); }

        public Builder insertBefore(String method, String descriptor, int bci, String assembly) {
            return add(Kind.INSERT_BEFORE, method, descriptor, bci, bci, assembly);
        }

        public Builder insertAfter(String method, String descriptor, int bci, String assembly) {
            return add(Kind.INSERT_AFTER, method, descriptor, bci, bci, assembly);
        }

        public Builder replace(String method, String descriptor, int bci, String assembly) {
            return add(Kind.REPLACE, method, descriptor, bci, bci, assembly);
        }

        public Builder delete(String method, String descriptor, int bci) {
            return delete(method, descriptor, bci, bci);
        }

        public Builder delete(String method, String descriptor, int fromBci, int toBci) {
            return add(Kind.DELETE, method, descriptor, fromBci, toBci, "");
        }

        /**
         * Inserts the snippet immediately before every return instruction in the method.
         * A non-void result is already on the operand stack and must remain there afterward.
         */
        public Builder insertBeforeReturns(String method, String descriptor, String assembly) {
            return add(Kind.INSERT_BEFORE_RETURNS, method, descriptor, -1, -1, assembly);
        }

        /**
         * Replaces every return instruction. The old non-void result remains on the operand stack;
         * the snippet must consume it and perform an appropriate return or throw.
         */
        public Builder replaceReturns(String method, String descriptor, String assembly) {
            return add(Kind.REPLACE_RETURNS, method, descriptor, -1, -1, assembly);
        }

        /** Adds a staged try/catch entry. End BCI is exclusive; type may be null for finally/catch-all. */
        public Builder addExceptionHandler(String method, String descriptor, int startBci,
                int endBci, int handlerBci, String type) {
            String exceptionType = type == null || type.trim().isEmpty() ? "*" : type.trim();
            return add(Kind.ADD_EXCEPTION_HANDLER, method, descriptor, startBci, endBci,
                    handlerBci + "|" + exceptionType);
        }

        /** Removes a try/catch table entry by the index shown by the handler-list command. */
        public Builder deleteExceptionHandler(String method, String descriptor, int index) {
            return add(Kind.DELETE_EXCEPTION_HANDLER, method, descriptor, index, index, "");
        }

        public Builder add(Kind kind, String method, String descriptor,
                int fromBci, int toBci, String assembly) {
            if (kind == null) throw new IllegalArgumentException("kind must not be null");
            operations.add(new Operation(kind, method, descriptor, fromBci, toBci, assembly));
            return this;
        }

        public JvmBytecodePatch build() { return new JvmBytecodePatch(className, operations); }
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value.trim();
    }
}
