package nhcm.jvmrtdp.api.bytecode;

/** Snapshot of one Code attribute exception-table entry. BCIs use instruction boundaries. */
public final class JvmExceptionHandlerInfo {
    private final int index;
    private final int startBci;
    private final int endBci;
    private final int handlerBci;
    private final String exceptionType;

    JvmExceptionHandlerInfo(int index, int startBci, int endBci,
            int handlerBci, String exceptionType) {
        this.index = index;
        this.startBci = startBci;
        this.endBci = endBci;
        this.handlerBci = handlerBci;
        this.exceptionType = exceptionType == null ? "<any/finally>" : exceptionType.replace('/', '.');
    }

    public int index() { return index; }
    public int startBci() { return startBci; }
    public int endBci() { return endBci; }
    public int handlerBci() { return handlerBci; }
    public String exceptionType() { return exceptionType; }

    @Override public String toString() {
        return "#" + index + " try " + startBci + ".." + endBci
                + " -> " + handlerBci + " catch " + exceptionType;
    }
}
