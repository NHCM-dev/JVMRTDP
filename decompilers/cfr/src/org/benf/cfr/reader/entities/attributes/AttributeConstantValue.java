package org.benf.cfr.reader.entities.attributes;

import org.benf.cfr.reader.entities.constantpool.ConstantPool;
import org.benf.cfr.reader.entities.constantpool.ConstantPoolEntry;
import org.benf.cfr.reader.util.bytestream.ByteData;
import org.benf.cfr.reader.util.output.Dumper;

public class AttributeConstantValue extends Attribute {
    public static final String ATTRIBUTE_NAME = "ConstantValue";

    private final ConstantPoolEntry value;

    public AttributeConstantValue(ByteData raw, ConstantPool cp) {
        this.value = cp.getEntry(raw.getU2At(0));
    }

    @Override
    public String getRawName() {
        return ATTRIBUTE_NAME;
    }

    @Override
    public Dumper dump(Dumper d) {
        return d.print("ConstantValue : " + value);
    }

    @Override
    public String toString() {
        return "ConstantValue : " + value;
    }

    public ConstantPoolEntry getValue() {
        return value;
    }
}
