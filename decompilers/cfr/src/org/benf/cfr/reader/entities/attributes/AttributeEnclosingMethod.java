package org.benf.cfr.reader.entities.attributes;

import org.benf.cfr.reader.util.bytestream.ByteData;
import org.benf.cfr.reader.util.output.Dumper;

public class AttributeEnclosingMethod extends Attribute {
    public static final String ATTRIBUTE_NAME = "EnclosingMethod";

    private final int classIndex;
    private final int methodIndex;

    public AttributeEnclosingMethod(ByteData raw) {
        this.classIndex = raw.getU2At(0);
        this.methodIndex = raw.getU2At(2);
    }

    @Override
    public String getRawName() {
        return ATTRIBUTE_NAME;
    }

    @Override
    public Dumper dump(Dumper d) {
        return d.print("EnclosingMethod");
    }

    @Override
    public String toString() {
        return "EnclosingMethod";
    }

    public int getClassIndex() {
        return classIndex;
    }

    public int getMethodIndex() {
        return methodIndex;
    }
}
