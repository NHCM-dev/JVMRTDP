package org.benf.cfr.reader.bytecode.analysis.structured.statement;

import org.benf.cfr.reader.bytecode.analysis.loc.BytecodeLoc;
import org.benf.cfr.reader.bytecode.analysis.parse.LValue;
import org.benf.cfr.reader.bytecode.analysis.parse.StatementContainer;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.ExpressionRewriter;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.scope.LValueScopeDiscoverer;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.scope.ScopeDiscoverInfoCache;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredScope;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.transformers.StructuredStatementTransformer;
import org.benf.cfr.reader.state.TypeUsageCollector;
import org.benf.cfr.reader.util.output.Dumper;

import java.util.List;

/*
 * Analysis-only synthetic scope node used by StructuredIf.traceLocalVariableScope to
 * give the instanceof pattern binding a per-branch (flow-region) scope instead of
 * making the whole `if` a shared scope of both branches (see issue #385 / the
 * lexical-vs-flow-scope problem). It is NEVER linked into the real structured tree
 * and never emitted; it only ever lives transiently on the scope-discoverer's block
 * stack. canDefine / markCreator delegate to the owning conditional statement
 * (StructuredIf, or a loop) so the existing InstanceOfAssignRewriter machinery
 * does the actual condition -> IOED rewrite.
 */
public class StructuredScopeEnvelope extends AbstractStructuredStatement {
    private final StructuredStatement owner;

    StructuredScopeEnvelope(StructuredStatement owner) {
        super(BytecodeLoc.NONE);
        this.owner = owner;
    }

    @Override
    public boolean canDefine(LValue scopedEntity, ScopeDiscoverInfoCache factCache) {
        return owner.canDefine(scopedEntity, factCache);
    }

    @Override
    public void markCreator(LValue scopedEntity, StatementContainer<StructuredStatement> hint) {
        owner.markCreator(scopedEntity, hint);
    }

    @Override
    public BytecodeLoc getCombinedLoc() {
        return getLoc();
    }

    @Override
    public void collectTypeUsages(TypeUsageCollector collector) {
    }

    @Override
    public Dumper dump(Dumper dumper) {
        // This should not be used in a valid tree - don't allow to escape.
        throw new IllegalStateException("leaked transient StructuredScopeEnvelope");
    }

    @Override
    public void traceLocalVariableScope(LValueScopeDiscoverer scopeDiscoverer) {
    }

    @Override
    public void transformStructuredChildren(StructuredStatementTransformer transformer, StructuredScope scope) {
    }

    @Override
    public void linearizeInto(List<StructuredStatement> out) {
    }

    @Override
    public void rewriteExpressions(ExpressionRewriter expressionRewriter) {
    }

    @Override
    public boolean isEffectivelyNOP() {
        return true;
    }
}
