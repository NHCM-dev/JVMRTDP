package org.benf.cfr.reader.entities.attributes;

import org.benf.cfr.reader.entities.constantpool.ConstantPool;
import org.benf.cfr.reader.entities.constantpool.ConstantPoolEntry;
import org.benf.cfr.reader.entities.constantpool.ConstantPoolEntryMethodHandle;
import org.benf.cfr.reader.entities.bootstrap.BootstrapMethodInfo;
import org.benf.cfr.reader.util.collections.ListFactory;
import org.benf.cfr.reader.util.bytestream.ByteData;
import org.benf.cfr.reader.util.output.Dumper;

import java.util.List;

public class AttributeBootstrapMethods extends Attribute {
    public static final String ATTRIBUTE_NAME = "BootstrapMethods";

    private final List<BootstrapMethodInfo> methodInfoList;

    public AttributeBootstrapMethods(ByteData raw, ConstantPool cp) {
        this.methodInfoList = decodeMethods(raw, cp);
    }

    public BootstrapMethodInfo getBootStrapMethodInfo(int idx) {
        if (idx < 0 || idx >= methodInfoList.size()) {
            throw new IllegalArgumentException("Invalid bootstrap index.");
        }
        return methodInfoList.get(idx);
    }

    private static List<BootstrapMethodInfo> decodeMethods(ByteData raw, ConstantPool cp) {

        List<BootstrapMethodInfo> res = ListFactory.newList();
        int numMethods = raw.getU2At(0);
        long offset = 2;
        for (int x = 0; x < numMethods; ++x) {
            int methodRef = raw.getU2At(offset);
            ConstantPoolEntryMethodHandle methodHandle = cp.getMethodHandleEntry(methodRef);

            offset += 2;
            int numBootstrapArguments = raw.getU2At(offset);
            offset += 2;
            ConstantPoolEntry[] bootstrapArguments = new ConstantPoolEntry[numBootstrapArguments];
            for (int y = 0; y < numBootstrapArguments; ++y) {
                bootstrapArguments[y] = cp.getEntry(raw.getU2At(offset));
                offset += 2;
            }
            res.add(new BootstrapMethodInfo(methodHandle, bootstrapArguments, cp));
        }
        return res;
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
