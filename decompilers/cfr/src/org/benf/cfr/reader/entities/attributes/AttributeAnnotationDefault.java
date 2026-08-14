package org.benf.cfr.reader.entities.attributes;

import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import org.benf.cfr.reader.entities.constantpool.ConstantPool;
import org.benf.cfr.reader.entities.annotations.ElementValue;
import org.benf.cfr.reader.state.TypeUsageCollector;
import org.benf.cfr.reader.util.bytestream.ByteData;
import org.benf.cfr.reader.util.output.Dumper;

public class AttributeAnnotationDefault extends Attribute {
    public static final String ATTRIBUTE_NAME = "AnnotationDefault";

    private final ElementValue elementValue;

    public AttributeAnnotationDefault(ByteData raw, ConstantPool cp) {
        Pair<Long, ElementValue> tmp = AnnotationHelpers.getElementValue(raw, 0, cp);
        this.elementValue = tmp.getSecond();
    }

    @Override
    public String getRawName() {
        return ATTRIBUTE_NAME;
    }

    @Override
    public Dumper dump(Dumper d) {
        return elementValue.dump(d);
    }

    @Override
    public String toString() {
        return "Annotationdefault : " + elementValue;
    }

    public ElementValue getElementValue() {
        return elementValue;
    }

    @Override
    public void collectTypeUsages(TypeUsageCollector collector) {
        elementValue.collectTypeUsages(collector);
    }
}
