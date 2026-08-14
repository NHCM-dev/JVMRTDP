package org.benf.cfr.reader.bytecode.analysis.structured.statement;

import org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.transformers.InstanceOfAssignRewriter;
import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.LValue;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.BooleanOperation;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.CompOp;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.ComparisonOperation;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.ConditionalExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.InstanceOfExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.InstanceOfExpressionDefining;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.Literal;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.NotOperation;
import org.benf.cfr.reader.bytecode.analysis.parse.StatementContainer;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.scope.LValueScopeDiscoverer;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.scope.ScopeDiscoverInfoCache;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredStatement;
import org.benf.cfr.reader.util.Troolean;

/*
 * Really, IOED (instanceof expression defining) has 'flow' scope - not lexical scope
 * We assume that all scoping in java is lexical scope, which is .... not correct here
 * and leads to us pushing definitions inside conditionals into both branches.
 *
 * This class (along with structured scope envelope) cheats, and simulates flow scope
 * by introducing a temporary inner lexical scope; we just need to determine the
 * correct branch, which is done with a parity check on negation.
 */
class InstanceofFlowScope {
    private enum FlowBranch { TRUE_BRANCH, ELSE_BRANCH, NONE }

    /*
     * The condition -> IOED lift, shared by every conditional statement (if / loop).
     * factCache caches "does this condition contain an instanceof" keyed by the owning
     * statement.
     */
    static boolean conditionCanDefine(StructuredStatement owner, ConditionalExpression cond, LValue scopedEntity, ScopeDiscoverInfoCache factCache) {
        Boolean hasInstanceOf = factCache.get(owner);
        if (hasInstanceOf == null) {
            hasInstanceOf = InstanceOfAssignRewriter.hasInstanceOf(cond);
            factCache.put(owner, hasInstanceOf);
        }
        if (!hasInstanceOf) return false;
        return new InstanceOfAssignRewriter(scopedEntity).isMatchFor(cond);
    }

    static ConditionalExpression conditionMarkCreator(ConditionalExpression cond, LValue scopedEntity) {
        return new InstanceOfAssignRewriter(scopedEntity).rewriteDefining(cond);
    }

    /*
     * Loop variant of trace(). For `while (o instanceof T t) BODY` the pattern
     * variable's flow region is the loop BODY (condition true -> enter body, matched;
     * false -> exit, not in scope after) - directly analogous to an if's then-branch,
     * so we wrap the body in an envelope. The negated form `while (!(o instanceof T t))`
     * has its flow region AFTER the loop (continuation that leaks past the loop
     * lexically) - the envelope can't represent that, so conservatively no lift.
     * (do-while is not handled here: its condition-bound pattern variable is, by
     * language rule, in scope only within the condition - there is no body region.)
     */
    static void traceLoopBody(StructuredStatement owner, ConditionalExpression cond, Op04StructuredStatement body, LValueScopeDiscoverer scopeDiscoverer) {
        boolean canDef = scopeDiscoverer.ifCanDefine();
        FlowBranch flow = (canDef && cond != null) ? instanceofFlowBranch(cond) : FlowBranch.NONE;

        if (flow == FlowBranch.TRUE_BRANCH) {
            StructuredScopeEnvelope env = newEnvelope(owner);
            scopeDiscoverer.enterBlock(env);
            cond.collectUsedLValues(scopeDiscoverer);
            scopeDiscoverer.processOp04Statement(body);
            scopeDiscoverer.leaveBlock(env);
        } else {
            if (cond != null) cond.collectUsedLValues(scopeDiscoverer);
            scopeDiscoverer.processOp04Statement(body);
        }
    }

    static void trace(StructuredIf owner, LValueScopeDiscoverer scopeDiscoverer) {
        ConditionalExpression cond = owner.conditionalExpression;
        Op04StructuredStatement ifTaken = owner.ifTaken;
        Op04StructuredStatement elseBlock = owner.elseBlock;

        boolean ifCanDefine = scopeDiscoverer.ifCanDefine();
        FlowBranch flow = ifCanDefine ? instanceofFlowBranch(cond) : FlowBranch.NONE;

        if (flow == FlowBranch.TRUE_BRANCH) {
            StructuredScopeEnvelope env = newEnvelope(owner);
            scopeDiscoverer.enterBlock(env);
            cond.collectUsedLValues(scopeDiscoverer);
            scopeDiscoverer.processOp04Statement(ifTaken);
            scopeDiscoverer.leaveBlock(env);
            if (elseBlock != null) {
                scopeDiscoverer.processOp04Statement(elseBlock);
            }
        } else if (flow == FlowBranch.ELSE_BRANCH && elseBlock != null) {
            scopeDiscoverer.processOp04Statement(ifTaken);
            StructuredScopeEnvelope env = newEnvelope(owner);
            scopeDiscoverer.enterBlock(env);
            cond.collectUsedLValues(scopeDiscoverer);
            scopeDiscoverer.processOp04Statement(elseBlock);
            scopeDiscoverer.leaveBlock(env);
        } else {
            cond.collectUsedLValues(scopeDiscoverer);
            scopeDiscoverer.processOp04Statement(ifTaken);
            if (elseBlock != null) {
                scopeDiscoverer.processOp04Statement(elseBlock);
            }
        }
    }

    private static StructuredScopeEnvelope newEnvelope(StructuredStatement owner) {
        StructuredScopeEnvelope env = new StructuredScopeEnvelope(owner);
        // Wiring it into a transient Op04 sets env.getContainer() so enterBlock can push it.
        new Op04StructuredStatement(env);
        return env;
    }

    private static FlowBranch instanceofFlowBranch(ConditionalExpression cond) {
        Troolean even = locateInstanceOf(cond, Troolean.TRUE);
        if (even == Troolean.NEITHER) return FlowBranch.NONE;
        return even == Troolean.TRUE ? FlowBranch.TRUE_BRANCH : FlowBranch.ELSE_BRANCH;
    }

    // Returns the negation parity (true == even == positive) at the first
    // InstanceOfExpression reachable from e, or null if there is none.
    // We only consider the first reachable one, this is enough to verify.
    //
    private static Troolean locateInstanceOf(Expression e, Troolean even) {
        if (e instanceof InstanceOfExpression || e instanceof InstanceOfExpressionDefining) {
            return even;
        }
        if (e instanceof NotOperation) {
            return locateInstanceOf(((NotOperation) e).getNegated(), even.negate());
        }
        if (e instanceof BooleanOperation) {
            BooleanOperation bo = (BooleanOperation) e;
            Troolean l = locateInstanceOf(bo.getLhs(), even);
            if (l != Troolean.NEITHER) return l;
            return locateInstanceOf(bo.getRhs(), even);
        }
        if (e instanceof ComparisonOperation) {
            ComparisonOperation co = (ComparisonOperation) e;
            CompOp op = co.getOp();
            Expression l = co.getLhs();
            Expression r = co.getRhs();
            boolean negate;
            if (op == CompOp.EQ) {
                negate = Literal.FALSE.equals(l) || Literal.FALSE.equals(r);
            } else if (op == CompOp.NE) {
                negate = Literal.TRUE.equals(l) || Literal.TRUE.equals(r);
            } else {
                negate = false;
            }
            Troolean p = negate ? even.negate() : even;
            Troolean res = locateInstanceOf(l, p);
            if (res != Troolean.NEITHER) return res;
            return locateInstanceOf(r, p);
        }
        return Troolean.NEITHER;
    }
}
