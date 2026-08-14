package org.benf.cfr.reader.bytecode.analysis.structured.statement;

import org.benf.cfr.reader.bytecode.analysis.loc.BytecodeLoc;
import org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.matchutil.MatchIterator;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.matchutil.MatchResultCollector;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.transformers.CanRemovePointlessBlock;
import org.benf.cfr.reader.bytecode.analysis.parse.LValue;
import org.benf.cfr.reader.bytecode.analysis.parse.StatementContainer;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.ConditionalExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.ExpressionRewriter;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.BlockIdentifier;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.scope.LValueScopeDiscoverer;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.scope.ScopeDiscoverInfoCache;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredScope;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.transformers.StructuredStatementTransformer;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.placeholder.ElseBlock;
import org.benf.cfr.reader.state.TypeUsageCollector;
import org.benf.cfr.reader.util.collections.ListFactory;
import org.benf.cfr.reader.util.output.Dumper;

import java.util.LinkedList;
import java.util.List;
import java.util.Vector;

public class StructuredIf extends AbstractStructuredStatement implements CanRemovePointlessBlock {

    ConditionalExpression conditionalExpression;
    Op04StructuredStatement ifTaken;
    Op04StructuredStatement elseBlock;

    public StructuredIf(BytecodeLoc loc, ConditionalExpression conditionalExpression, Op04StructuredStatement ifTaken) {
        this(loc, conditionalExpression, ifTaken, null);
    }

    public StructuredIf(BytecodeLoc loc, ConditionalExpression conditionalExpression, Op04StructuredStatement ifTaken, Op04StructuredStatement elseBlock) {
        super(loc);
        this.conditionalExpression = conditionalExpression;
        this.ifTaken = ifTaken;
        this.elseBlock = elseBlock;
    }


    @Override
    public void collectTypeUsages(TypeUsageCollector collector) {
        conditionalExpression.collectTypeUsages(collector);
        collector.collectFrom(ifTaken);
        collector.collectFrom(elseBlock);
    }

    @Override
    public BytecodeLoc getCombinedLoc() {
        return BytecodeLoc.combine(this, conditionalExpression);
    }

    @Override
    public Dumper dump(Dumper dumper) {
        dumper.keyword("if ").separator("(").dump(conditionalExpression).separator(") ");
        ifTaken.dump(dumper);
        if (elseBlock != null) {
            dumper.removePendingCarriageReturn();
            dumper.print(" else ");
            elseBlock.dump(dumper);
        }
        return dumper;
    }

    public boolean hasElseBlock() {
        return elseBlock != null;
    }

    public ConditionalExpression getConditionalExpression() {
        return conditionalExpression;
    }

    public void setConditionalExpression(ConditionalExpression conditionalExpression) {
        this.conditionalExpression = conditionalExpression;
    }

    public Op04StructuredStatement getIfTaken() {
        return ifTaken;
    }

    @Override
    public StructuredStatement informBlockHeirachy(Vector<BlockIdentifier> blockIdentifiers) {
        ifTaken.informBlockMembership(blockIdentifiers);
        if (elseBlock != null) elseBlock.informBlockMembership(blockIdentifiers);
        return null;
    }

    @Override
    public boolean isScopeBlock() {
        return true;
    }

    @Override
    public void transformStructuredChildren(StructuredStatementTransformer transformer, StructuredScope scope) {
        ifTaken.transform(transformer, scope);
        if (elseBlock != null) elseBlock.transform(transformer, scope);
    }

    @Override
    public void linearizeInto(List<StructuredStatement> out) {
        out.add(this);
        ifTaken.linearizeStatementsInto(out);
        if (elseBlock != null) {
            out.add(new ElseBlock());
            elseBlock.linearizeStatementsInto(out);
        }
    }

    // Since j14 (jep305), an instanceof can match a pattern - thus a variable can be defined
    // inside the test of an instance block (note - this only handles if, we need to extend to
    // ternaries, loop condition checks etc).
    @Override
    public void traceLocalVariableScope(LValueScopeDiscoverer scopeDiscoverer) {
        InstanceofFlowScope.trace(this, scopeDiscoverer);
    }

    // We will be asked if we CAN define something if we're allowing if statements to define
    // instanceofs.
    @Override
    public boolean canDefine(LValue scopedEntity, ScopeDiscoverInfoCache factCache) {
        return InstanceofFlowScope.conditionCanDefine(this, conditionalExpression, scopedEntity, factCache);
    }

    @Override
    public void markCreator(LValue scopedEntity, StatementContainer<StructuredStatement> hint) {
        this.conditionalExpression = InstanceofFlowScope.conditionMarkCreator(this.conditionalExpression, scopedEntity);
    }

    @Override
    public boolean isRecursivelyStructured() {
        if (!ifTaken.isFullyStructured()) return false;
        if (elseBlock != null && !elseBlock.isFullyStructured()) return false;
        return true;
    }

    @Override
    public boolean fallsNopToNext() {
        return true;
    }

    @Override
    public boolean match(MatchIterator<StructuredStatement> matchIterator, MatchResultCollector matchResultCollector) {
        StructuredStatement o = matchIterator.getCurrent();
        if (!(o instanceof StructuredIf)) return false;
        StructuredIf other = (StructuredIf) o;
        if (!conditionalExpression.equals(other.conditionalExpression)) return false;

        matchIterator.advance();
        return true;
    }

    @Override
    public void rewriteExpressions(ExpressionRewriter expressionRewriter) {
        conditionalExpression = expressionRewriter.rewriteExpression(conditionalExpression, null, this.getContainer(), null);
    }

    public StructuredStatement convertToAssertion(StructuredAssert structuredAssert) {
        if (elseBlock == null) {
            return structuredAssert;
        }
        /* For some reason, we've created an assert with an else block
         * (a different optimisation must have thought it made sense!)
         */
        LinkedList<Op04StructuredStatement> list = ListFactory.newLinkedList();
        list.add(new Op04StructuredStatement(structuredAssert));
        list.add(elseBlock);
        return new Block(list, false);
    }

    @Override
    public void removePointlessBlocks(StructuredScope scope) {
        if (elseBlock != null && elseBlock.getStatement().isEffectivelyNOP()) {
            elseBlock = null;
        }
    }
}
