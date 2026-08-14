package org.benf.cfr.reader.entities.attributes;

import java.util.Collections;
import java.util.List;

import org.benf.cfr.reader.entities.constantpool.ConstantPool;
import org.benf.cfr.reader.entityfactories.AttributeFactory;
import org.benf.cfr.reader.util.ClassFileVersion;
import org.benf.cfr.reader.util.bytestream.ByteData;
import org.benf.cfr.reader.util.collections.ListFactory;
import org.benf.cfr.reader.util.output.Dumper;

public class AttributeRecord extends Attribute {
    public static final String ATTRIBUTE_NAME = "Record";

    public static class RecordComponentInfo {
        private final String name;
        private final String descriptor;
        private final List<Attribute> attributes;

        public RecordComponentInfo(String name, String descriptor, List<Attribute> attributes) {
            this.name = name;
            this.descriptor = descriptor;
            this.attributes = attributes;
        }

        public String getName() {
            return name;
        }

        public String getDescriptor() {
            return descriptor;
        }

        public List<Attribute> getAttributes() {
            // Prevent accidental modification
            return Collections.unmodifiableList(attributes);
        }
    }

    private final List<RecordComponentInfo> componentInfos;

    public AttributeRecord(ByteData raw, ConstantPool cp, ClassFileVersion classFileVersion) {
        int numComponents = raw.getU2At(0);
        long offset = 2;

        componentInfos = ListFactory.newList();
        for (int i = 0; i < numComponents; i++) {
            int nameIndex = raw.getU2At(offset);
            offset += 2;
            String name = cp.getUTF8Entry(nameIndex).getValue();

            int descriptorIndex = raw.getU2At(offset);
            offset += 2;
            String descriptor = cp.getUTF8Entry(descriptorIndex).getValue();

            int attributesCount = raw.getU2At(offset);
            offset += 2;

            List<Attribute> attributes = ListFactory.newList(attributesCount);
            raw = raw.getOffsetData(offset);
            offset = AttributeFactory.readAttributes(raw, cp, classFileVersion, attributesCount, attributes);

            componentInfos.add(new RecordComponentInfo(name, descriptor, attributes));
        }
    }

    public List<Attribute> getRecordComponentAttributes(String componentName) {
        for (RecordComponentInfo componentInfo : componentInfos) {
            if (componentInfo.getName().equals(componentName)) {
                return componentInfo.getAttributes();
            }
        }
        return Collections.emptyList();
    }

    @Override
    public String getRawName() {
        return ATTRIBUTE_NAME;
    }

    @Override
    public Dumper dump(Dumper d) {
        return d.print(ATTRIBUTE_NAME);
    }
}
