package nhcm.jvmrtdp.handles.search;

/** Fluent controller-side query shared by loaded fields and methods. */
public class RemoteMemberQuery {
    private String classGlob = "*";
    private String nameGlob = "*";
    private String typeGlob = "*";
    private String parametersGlob = "*";
    private String mode = "all";
    private int limit = 200;

    public RemoteMemberQuery owner(String glob) { classGlob = value(glob, "*"); return this; }
    public RemoteMemberQuery name(String glob) { nameGlob = value(glob, "*"); return this; }
    /** Field type or method return type, expressed as a Java name or glob. */
    public RemoteMemberQuery type(String glob) { typeGlob = value(glob, "*"); return this; }
    /** Comma-separated Java parameter type names, with glob support. */
    public RemoteMemberQuery parameters(String glob) { parametersGlob = value(glob, "*"); return this; }
    public RemoteMemberQuery mode(String value) { mode = value(value, "all"); return this; }
    public RemoteMemberQuery limit(int value) {
        if (value < 1 || value > 10000) throw new IllegalArgumentException("limit must be between 1 and 10000");
        limit = value;
        return this;
    }

    public String classGlob() { return classGlob; }
    public String nameGlob() { return nameGlob; }
    public String typeGlob() { return typeGlob; }
    public String parametersGlob() { return parametersGlob; }
    public String mode() { return mode; }
    public int limit() { return limit; }

    private static String value(String actual, String fallback) {
        return actual == null || actual.isEmpty() ? fallback : actual;
    }
}
