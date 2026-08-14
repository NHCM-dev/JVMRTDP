package org.benf.cfr.reader.entities.attributes;

import org.benf.cfr.reader.util.output.Dumper;

public class AttributeModuleClassMain extends Attribute {
    public static final String ATTRIBUTE_NAME = "ModuleClassMain";

    public AttributeModuleClassMain() {
    }

    @Override
    public String getRawName() {
        return ATTRIBUTE_NAME;
    }

    @Override
    public Dumper dump(Dumper d) {
        return d.print(ATTRIBUTE_NAME);
    }

    @Override
    public String toString() {
        return ATTRIBUTE_NAME;
    }
}
