package org.benf.cfr.reader.entityfactories;

import org.benf.cfr.reader.entities.constantpool.ConstantPool;
import org.benf.cfr.reader.entities.constantpool.ConstantPoolEntryUTF8;
import org.benf.cfr.reader.entities.attributes.*;
import org.benf.cfr.reader.util.ClassFileVersion;
import org.benf.cfr.reader.util.MiscUtils;
import org.benf.cfr.reader.util.bytestream.ByteData;
import org.benf.cfr.reader.util.bytestream.OffsettingByteData;

import java.util.ArrayList;
import java.util.List;

public class AttributeFactory {
    private static final long OFFSET_OF_ATTRIBUTE_NAME_INDEX = 0;
    private static final long OFFSET_OF_ATTRIBUTE_LENGTH_INDEX = OFFSET_OF_ATTRIBUTE_NAME_INDEX + 2;
    private static final long OFFSET_OF_ATTRIBUTE_DATA_INDEX = OFFSET_OF_ATTRIBUTE_LENGTH_INDEX + 4;

    private static Attribute readAttribute(ByteData raw, ConstantPool cp, ClassFileVersion classFileVersion, String attributeName) {
        /*
         * This absolutely could be replaced with a string switch, but I'm sticking to j6,
         * as I want to mandate the minimum sane requirements.
         */
        if (AttributeCode.ATTRIBUTE_NAME.equals(attributeName)) {
            // Code attribute needs the signature of the method, so that we have type information for the
            // local variables.
            return new AttributeCode(raw, cp, classFileVersion);
        }

        try {
            if (AttributeLocalVariableTable.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeLocalVariableTable(raw);
            } else if (AttributeSignature.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeSignature(raw, cp);
            } else if (AttributeConstantValue.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeConstantValue(raw, cp);
            } else if (AttributeLineNumberTable.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeLineNumberTable(raw);
            } else if (AttributeExceptions.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeExceptions(raw, cp);
            } else if (AttributeEnclosingMethod.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeEnclosingMethod(raw);
            } else if (AttributeDeprecated.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeDeprecated();
            } else if (AttributeRuntimeVisibleAnnotations.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeRuntimeVisibleAnnotations(raw, cp);
            } else if (AttributeRuntimeVisibleTypeAnnotations.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeRuntimeVisibleTypeAnnotations(raw, cp);
            } else if (AttributeRuntimeInvisibleTypeAnnotations.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeRuntimeInvisibleTypeAnnotations(raw, cp);
            } else if (AttributeRuntimeInvisibleAnnotations.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeRuntimeInvisibleAnnotations(raw, cp);
            } else if (AttributeRuntimeVisibleParameterAnnotations.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeRuntimeVisibleParameterAnnotations(raw, cp);
            } else if (AttributeRuntimeInvisibleParameterAnnotations.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeRuntimeInvisibleParameterAnnotations(raw, cp);
            } else if (AttributeSourceFile.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeSourceFile();
            } else if (AttributeInnerClasses.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeInnerClasses(raw, cp);
            } else if (AttributeBootstrapMethods.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeBootstrapMethods(raw, cp);
            } else if (AttributeAnnotationDefault.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeAnnotationDefault(raw, cp);
            } else if (AttributeLocalVariableTypeTable.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeLocalVariableTypeTable();
            } else if (AttributeStackMapTable.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeStackMapTable(raw, cp);
            } else if (AttributeSynthetic.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeSynthetic();
            } else if (AttributeScalaSig.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeScalaSig();
            } else if (AttributeScala.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeScala();
            } else if (AttributeModule.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeModule(raw, cp);
            } else if (AttributeModulePackages.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeModulePackages();
            } else if (AttributeModuleClassMain.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeModuleClassMain();
            } else if (AttributeRecord.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributeRecord(raw, cp, classFileVersion);
            } else if (AttributePermittedSubclasses.ATTRIBUTE_NAME.equals(attributeName)) {
                return new AttributePermittedSubclasses(raw, cp);
            }
        } catch (Exception e) {
            // Can't handle it? Continue and process as an unknown attribute.
            MiscUtils.handyBreakPoint();
        }
        return new AttributeUnknown(attributeName);
    }

    /**
     * Reads {@code count} attributes into {@code attributes} and returns the total number of consumed bytes.
     */
    public static long readAttributes(ByteData raw, ConstantPool cp, ClassFileVersion classFileVersion, int count, List<Attribute> attributes) {
        long bytesRead = 0;
        OffsettingByteData offsettingByteData = raw.getOffsettingOffsetData(0);
        for (; count > 0; --count) {
            // All attributes have the same basic structure, see https://docs.oracle.com/javase/specs/jvms/se25/html/jvms-4.html#jvms-4.7

            int nameIndex = offsettingByteData.getU2At(OFFSET_OF_ATTRIBUTE_NAME_INDEX);
            ConstantPoolEntryUTF8 name = (ConstantPoolEntryUTF8) cp.getEntry(nameIndex);
            String attributeName = name.getValue();

            long attributeLength = offsettingByteData.getU4At(OFFSET_OF_ATTRIBUTE_LENGTH_INDEX);

            offsettingByteData.advance(OFFSET_OF_ATTRIBUTE_DATA_INDEX);
            bytesRead += OFFSET_OF_ATTRIBUTE_DATA_INDEX;

            attributes.add(readAttribute(offsettingByteData, cp, classFileVersion, attributeName));

            offsettingByteData.advance(attributeLength);
            bytesRead += attributeLength;
        }

        return bytesRead;
    }

    public static List<Attribute> readAttributes(ByteData raw, ConstantPool cp, ClassFileVersion classFileVersion, int count) {
        List<Attribute> attributes = new ArrayList<Attribute>(count);
        readAttributes(raw, cp, classFileVersion, count, attributes);
        return attributes;
    }
}
