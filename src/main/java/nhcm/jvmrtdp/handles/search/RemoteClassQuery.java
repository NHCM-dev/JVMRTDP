package nhcm.jvmrtdp.handles.search;

/** Fluent controller-side query for loaded classes. */
public class RemoteClassQuery {
    private String nameGlob = "*";
    private String kind = "any";
    private String packageGlob = "*";
    private String extendsGlob = "";
    private String implementsGlob = "";
    private int limit = 200;

    public RemoteClassQuery name(String glob) { nameGlob = value(glob, "*"); return this; }
    public RemoteClassQuery kind(String value) { kind = value(value, "any"); return this; }
    public RemoteClassQuery inPackage(String glob) { packageGlob = value(glob, "*"); return this; }
    public RemoteClassQuery extending(String glob) { extendsGlob = value(glob, ""); return this; }
    public RemoteClassQuery implementing(String glob) { implementsGlob = value(glob, ""); return this; }
    public RemoteClassQuery limit(int value) {
        if (value < 1 || value > 10000) throw new IllegalArgumentException("limit must be between 1 and 10000");
        limit = value;
        return this;
    }

    public String nameGlob() { return nameGlob; }
    public String kind() { return kind; }
    public String packageGlob() { return packageGlob; }
    public String extendsGlob() { return extendsGlob; }
    public String implementsGlob() { return implementsGlob; }
    public int limit() { return limit; }

    private static String value(String actual, String fallback) {
        return actual == null || actual.isEmpty() ? fallback : actual;
    }
}
