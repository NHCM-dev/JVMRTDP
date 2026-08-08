package nhcm.jvmrtdp.handles.java;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RemoteClassInfo {
    private final String name;
    private final int modifiers;
    private final String superclass;
    private final List<String> interfaces;
    private final boolean interfaceType;
    private final boolean enumType;
    private final boolean arrayType;

    public RemoteClassInfo(
            String name, int modifiers, String superclass, List<String> interfaces,
            boolean interfaceType, boolean enumType, boolean arrayType) {
        this.name = name;
        this.modifiers = modifiers;
        this.superclass = superclass;
        this.interfaces = Collections.unmodifiableList(new ArrayList<String>(interfaces));
        this.interfaceType = interfaceType;
        this.enumType = enumType;
        this.arrayType = arrayType;
    }

    public String name() { return name; }
    public int modifiers() { return modifiers; }
    public String superclass() { return superclass; }
    public List<String> interfaces() { return interfaces; }
    public boolean isInterface() { return interfaceType; }
    public boolean isEnum() { return enumType; }
    public boolean isArray() { return arrayType; }
}
