package org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters;

import org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.transformers.ExpressionRewriterTransformer;
import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.LValue;
import org.benf.cfr.reader.bytecode.analysis.parse.StatementContainer;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.AssignmentExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.ConditionalExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.InstanceOfExpressionDefining;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.LValueExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.LocalVariable;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.AbstractExpressionRewriter;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.ExpressionRewriter;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.ExpressionRewriterFlags;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.SSAIdentifiers;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredScope;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.AbstractStructuredConditionalLoopStatement;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredAssignment;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredDefinition;
import org.benf.cfr.reader.util.collections.ListFactory;
import org.benf.cfr.reader.util.collections.MapFactory;
import org.benf.cfr.reader.util.collections.SetFactory;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class InstanceofMatchTidyingRewriter {
    // Shape (A): `(foo = bar) instanceof T fred`. foo is removable iff it is read nowhere
    // else; that is decided in the post-pass once this whole-method read count is complete.
    private final Map<LocalVariable, Integer> locals = MapFactory.newMap();
    private final Set<LocalVariable> removeCandidates = SetFactory.newOrderedSet();
    private final Map<LValue, List<StructuredStatement>> definitions = MapFactory.newOrderedMap();

    // Shape (B): `foo = bar; ... <cond>(foo instanceof T s)`.
    // A whole-method read count is too coarse here: a reused synthetic slot is one
    // LocalVariable shared by several independent single-use live ranges, so a method-wide
    // count over-counts and a mid-pass count under-counts (it misses reads after the test,
    // e.g. in a loop body). Soundness is therefore decided per live range: the fold is valid
    // iff, between foo's defining assignment and its next reassignment, foo's only read is
    // the instanceof itself.
    private final Map<LocalVariable, List<StructuredAssignment>> localAssignments = MapFactory.newOrderedMap();
    private final Map<LocalVariable, Integer> readsSinceDef = MapFactory.newMap();
    private final Map<LocalVariable, StructuredAssignment> currentDef = MapFactory.newMap();
    private final Map<LocalVariable, InstanceOfExpressionDefining> pendingSite = MapFactory.newMap();
    // The specific instanceof nodes (by identity) whose live range proved foldable, mapped to
    // the value to inline. The substitution is always applied; deleting the now-redundant
    // defining assignment is left to the shared definition filter, so a reused slot whose
    // value is still needed by a later range keeps its declaration.
    private final Map<InstanceOfExpressionDefining, Expression> foldSites = new IdentityHashMap<InstanceOfExpressionDefining, Expression>();

    public static void rewrite(Op04StructuredStatement block) {
        new InstanceofMatchTidyingRewriter().doRewrite(block);
    }

    private void doRewrite(Op04StructuredStatement block) {
        ExpressionRewriterTransformer et = new SearchPass(new SearchPassRewriter());
        et.transform(block);
        for (LocalVariable foo : ListFactory.newList(pendingSite.keySet())) {
            finalizeRange(foo);
        }

        removeCandidates.removeAll(locals.keySet());
        //noinspection SuspiciousMethodCalls
        removeCandidates.retainAll(definitions.keySet());

        if (removeCandidates.isEmpty() && foldSites.isEmpty()) return;

        et = new ExpressionRewriterTransformer(new AssignRemover());
        et.transform(block);

        if (!removeCandidates.isEmpty()) {
            for (List<StructuredStatement> definitionList : definitions.values()) {
                for (StructuredStatement definition : definitionList) {
                    definition.getContainer().nopOut();
                }
            }
        }
    }

    // Closes foo's current live range. The pending instanceof folds iff foo's only read in
    // this range is the lvalue of its own defining assignment (count 1) - the instanceof read
    // is suppressed and so left uncounted, and bar must not be reassignable so it carries the
    // same value at the assignment and at the test.
    private void finalizeRange(LocalVariable foo) {
        InstanceOfExpressionDefining site = pendingSite.remove(foo);
        if (site == null) return;
        StructuredAssignment def = currentDef.get(foo);
        if (def == null) return;
        Integer reads = readsSinceDef.get(foo);
        if (reads == null || reads != 1) return;
        Expression rhs = def.getRvalue();
        if (!(rhs instanceof LValueExpression)) return;
        LValue bar = ((LValueExpression) rhs).getLValue();
        if (!(bar instanceof LocalVariable) || bar.equals(foo)) return;
        List<StructuredAssignment> barDefs = localAssignments.get(bar);
        if (barDefs != null && barDefs.size() > 1) return;
        foldSites.put(site, rhs);
        // Make the now-redundant defining assignment eligible for deletion, but only via the
        // shared filter: removeCandidates is later intersected with "not read anywhere else"
        // and "has a definition". Only this range's own defining-lvalue read is discounted
        // (it is going away); any other read of foo keeps it live, so a reused slot still
        // needed by a later range keeps its declaration and only the substitution applies.
        removeCandidates.add(foo);
        addDefinition(def, foo);
        Integer c = locals.get(foo);
        if (c != null) {
            if (c <= 1) locals.remove(foo);
            else locals.put(foo, c - 1);
        }
    }

    private void addDefinition(StructuredStatement in, LValue lvalue) {
        List<StructuredStatement> defl = definitions.get(lvalue);
        if (defl == null) {
            defl = ListFactory.newList();
            definitions.put(lvalue, defl);
        }
        defl.add(in);
    }

    private void noteAssignment(LocalVariable lvalue, StructuredAssignment in) {
        finalizeRange(lvalue);
        List<StructuredAssignment> defl = localAssignments.get(lvalue);
        if (defl == null) {
            defl = ListFactory.newList();
            localAssignments.put(lvalue, defl);
        }
        defl.add(in);
        currentDef.put(lvalue, in);
        readsSinceDef.put(lvalue, 0);
    }

    private class SearchPass extends ExpressionRewriterTransformer {
        SearchPass(ExpressionRewriter expressionRewriter) {
            super(expressionRewriter);
        }

        @Override
        public StructuredStatement transform(StructuredStatement in, StructuredScope scope) {
            if (in instanceof StructuredDefinition) {
                addDefinition(in, ((StructuredDefinition) in).getLvalue());
            } else if (in instanceof StructuredAssignment) {
                LValue lvalue = ((StructuredAssignment) in).getLvalue();
                if (lvalue instanceof LocalVariable) {
                    noteAssignment((LocalVariable) lvalue, (StructuredAssignment) in);
                }
            }
            return super.transform(in, scope);
        }
    }

    private class SearchPassRewriter extends AbstractExpressionRewriter {
        // Returns the (unmodified) expression when a fold shape is recognised so the instanceof
        // subtree is not descended into - that leaves foo's pattern-internal read uncounted,
        // which is what makes the read counts mean "read other than by the instanceof". Returns
        // null to fall through. InstanceOfExpressionDefining is a ConditionalExpression and only
        // ever occupies ConditionalExpression slots, so it is only reached via that overload.
        private ConditionalExpression handleIfDefining(ConditionalExpression expression, SSAIdentifiers ssaIdentifiers, StatementContainer statementContainer, ExpressionRewriterFlags flags) {
            if (!(expression instanceof InstanceOfExpressionDefining)) return null;
            InstanceOfExpressionDefining expressionDefining = (InstanceOfExpressionDefining) expression;
            Expression lhs = expressionDefining.getLhs();
            if (lhs instanceof AssignmentExpression && ((AssignmentExpression) lhs).getlValue() instanceof LocalVariable) {
                LocalVariable al = (LocalVariable) ((AssignmentExpression) lhs).getlValue();
                // This inline assignment ends al's previous live range: commit any pending
                // shape (B) fold using only that range's reads, then start a fresh range
                // (an inline assignment is not a deletable defining statement).
                finalizeRange(al);
                currentDef.remove(al);
                readsSinceDef.put(al, 0);
                ((AssignmentExpression) lhs).getrValue().applyExpressionRewriter(this, ssaIdentifiers, statementContainer, flags);
                removeCandidates.add(al);
                return expression;
            } else if (lhs instanceof LValueExpression && ((LValueExpression) lhs).getLValue() instanceof LocalVariable
                    && !(statementContainer.getStatement() instanceof AbstractStructuredConditionalLoopStatement)) {
                // A loop condition is re-evaluated after each iteration, so inlining foo's
                // initial value here would ignore any reassignment of foo in the loop body.
                // Loop pattern lifting is handled separately; shape (B) is if/ternary only.
                LocalVariable foo = (LocalVariable) ((LValueExpression) lhs).getLValue();
                if (currentDef.containsKey(foo)) {
                    pendingSite.put(foo, expressionDefining);
                    return expression;
                }
            }
            return null;
        }

        @Override
        public Expression rewriteExpression(Expression expression, SSAIdentifiers ssaIdentifiers, StatementContainer statementContainer, ExpressionRewriterFlags flags) {
            if (expression instanceof AssignmentExpression && ((AssignmentExpression) expression).getlValue() instanceof LocalVariable) {
                // Any reassignment of a local ends its live range, even one buried in a plain
                // instanceof or other expression. Commit the prior range's pending fold, then
                // start a fresh range (an inline assignment is not a deletable definition).
                LocalVariable al = (LocalVariable) ((AssignmentExpression) expression).getlValue();
                finalizeRange(al);
                currentDef.remove(al);
                readsSinceDef.put(al, 0);
            }
            return super.rewriteExpression(expression, ssaIdentifiers, statementContainer, flags);
        }

        @Override
        public ConditionalExpression rewriteExpression(ConditionalExpression expression, SSAIdentifiers ssaIdentifiers, StatementContainer statementContainer, ExpressionRewriterFlags flags) {
            ConditionalExpression handled = handleIfDefining(expression, ssaIdentifiers, statementContainer, flags);
            if (handled != null) return handled;
            return super.rewriteExpression(expression, ssaIdentifiers, statementContainer, flags);
        }

        @Override
        public LValue rewriteExpression(LValue lValue, SSAIdentifiers ssaIdentifiers, StatementContainer statementContainer, ExpressionRewriterFlags flags) {
            if (lValue instanceof LocalVariable) {
                Integer prev = locals.get(lValue);
                locals.put((LocalVariable)lValue, prev == null ? 1 : prev+1);
                Integer rng = readsSinceDef.get(lValue);
                if (rng != null) readsSinceDef.put((LocalVariable) lValue, rng + 1);
            }
            return super.rewriteExpression(lValue, ssaIdentifiers, statementContainer, flags);
        }
    }

    private class AssignRemover extends AbstractExpressionRewriter {
        private ConditionalExpression handleIfDefining(ConditionalExpression expression) {
            if (!(expression instanceof InstanceOfExpressionDefining)) return null;
            InstanceOfExpressionDefining defining = (InstanceOfExpressionDefining) expression;
            Expression lhs = defining.getLhs();
            //noinspection SuspiciousMethodCalls
            if (lhs instanceof AssignmentExpression && removeCandidates.contains(((AssignmentExpression) lhs).getlValue())) {
                return defining.withReplacedExpression(((AssignmentExpression) lhs).getrValue());
            }
            Expression folded = foldSites.get(defining);
            if (folded != null) {
                return defining.withReplacedExpression(folded);
            }
            return null;
        }

        @Override
        public ConditionalExpression rewriteExpression(ConditionalExpression expression, SSAIdentifiers ssaIdentifiers, StatementContainer statementContainer, ExpressionRewriterFlags flags) {
            ConditionalExpression handled = handleIfDefining(expression);
            if (handled != null) return handled;
            return super.rewriteExpression(expression, ssaIdentifiers, statementContainer, flags);
        }
    }
}
