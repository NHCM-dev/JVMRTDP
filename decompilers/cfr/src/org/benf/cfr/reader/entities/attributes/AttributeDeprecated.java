package org.benf.cfr.reader.entities.attributes;

import org.benf.cfr.reader.util.output.Dumper;

public class AttributeDeprecated extends Attribute {
    public static final String ATTRIBUTE_NAME = "Deprecated";

    public AttributeDeprecated() {
    }

    @Override
    public String getRawName() {
        return ATTRIBUTE_NAME;
    }

    @Override
    public Dumper dump(Dumper d) {
        return d.print("Deprecated");
    }

    @Override
    public String toString() {
        return "Deprecated";
    }
}
