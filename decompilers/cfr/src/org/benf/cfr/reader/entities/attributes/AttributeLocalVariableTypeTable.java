package org.benf.cfr.reader.entities.attributes;

import org.benf.cfr.reader.util.output.Dumper;

public class AttributeLocalVariableTypeTable extends Attribute {
    public final static String ATTRIBUTE_NAME = "LocalVariableTypeTable";

    public AttributeLocalVariableTypeTable() {
    }

    @Override
    public String getRawName() {
        return ATTRIBUTE_NAME;
    }

    @Override
    public Dumper dump(Dumper d) {
        return d;
    }
}
