package org.benf.cfr.reader.bytecode.analysis.parse.pattern;

import org.benf.cfr.reader.bytecode.analysis.parse.LValue;
import org.benf.cfr.reader.bytecode.analysis.parse.Pattern;
import org.benf.cfr.reader.bytecode.analysis.types.discovery.InferredJavaType;
import org.benf.cfr.reader.state.TypeUsageCollector;
import org.benf.cfr.reader.util.StringUtils;
import org.benf.cfr.reader.util.collections.ListFactory;
import org.benf.cfr.reader.util.output.Dumper;

import java.util.List;

public class RecordPattern implements Pattern {
    private final InferredJavaType type;
    private final List<Pattern> params;

    public RecordPattern(InferredJavaType type, List<Pattern> params) {
        this.type = type;
        this.params = params;
    }

    @Override
    public InferredJavaType getInferredJavaType() {
        return type;
    }

    @Override
    public Dumper dump(Dumper d, boolean defines) {
        d.dump(type.getJavaTypeInstance());
        d.print('(');
        boolean first = true;
        for (Pattern p : params) {
            first = StringUtils.comma(first, d);
            p.dump(d, defines);
        }
        d.print(')');
        return d;
    }

    @Override
    public Dumper dump(Dumper d) {
        return dump(d, true);
    }

    @Override
    public void collectTypeUsages(TypeUsageCollector collector) {
        collector.collect(type.getJavaTypeInstance());
        for (Pattern p : params) {
            p.collectTypeUsages(collector);
        }
    }

    @Override
    public List<LValue> getDeclaredLValues() {
        List<LValue> out = ListFactory.newList();
        for (Pattern p : params) {
            out.addAll(p.getDeclaredLValues());
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RecordPattern)) return false;
        RecordPattern other = (RecordPattern) o;
        return type.getJavaTypeInstance().equals(other.type.getJavaTypeInstance())
                && params.equals(other.params);
    }

    @Override
    public int hashCode() {
        return type.getJavaTypeInstance().hashCode() * 31 + params.hashCode();
    }
}
