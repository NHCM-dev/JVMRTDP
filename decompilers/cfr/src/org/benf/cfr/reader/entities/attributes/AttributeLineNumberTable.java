package org.benf.cfr.reader.entities.attributes;

import org.benf.cfr.reader.util.bytestream.ByteData;
import org.benf.cfr.reader.util.output.Dumper;

import java.util.NavigableMap;
import java.util.TreeMap;

public class AttributeLineNumberTable extends Attribute {
    public static final String ATTRIBUTE_NAME = "LineNumberTable";

    private static final long OFFSET_OF_ENTRY_COUNT = 0;
    private static final long OFFSET_OF_ENTRIES = 2;

    private final NavigableMap<Integer, Integer> entries = new TreeMap<Integer, Integer>();


    public AttributeLineNumberTable(ByteData raw) {
        int numLineNumbers = raw.getU2At(OFFSET_OF_ENTRY_COUNT);
        long offset = OFFSET_OF_ENTRIES;
        for (int x = 0; x < numLineNumbers; ++x, offset += 4) {
            int startPc = raw.getU2At(offset);
            int lineNumber = raw.getU2At(offset + 2);
            entries.put( startPc, lineNumber);
        }
    }

    public boolean hasEntries() {
        return !entries.isEmpty();
    }

    public int getStartLine() {
        return entries.firstEntry().getValue();
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

    public NavigableMap<Integer, Integer> getEntries() {
        return entries;
    }
}
