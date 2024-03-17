/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.trino.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.trino.sql.ir.CoalesceExpression;
import io.trino.sql.ir.Constant;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import io.trino.sql.planner.iterative.rule.test.PlanBuilder;
import io.trino.sql.planner.plan.Assignments;
import io.trino.sql.planner.plan.JoinNode.EquiJoinClause;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.sql.planner.assertions.PlanMatchPattern.aggregation;
import static io.trino.sql.planner.assertions.PlanMatchPattern.aggregationFunction;
import static io.trino.sql.planner.assertions.PlanMatchPattern.expression;
import static io.trino.sql.planner.assertions.PlanMatchPattern.globalAggregation;
import static io.trino.sql.planner.assertions.PlanMatchPattern.join;
import static io.trino.sql.planner.assertions.PlanMatchPattern.project;
import static io.trino.sql.planner.assertions.PlanMatchPattern.singleGroupingSet;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static io.trino.sql.planner.plan.AggregationNode.Step.SINGLE;
import static io.trino.sql.planner.plan.AggregationNode.groupingSets;
import static io.trino.sql.planner.plan.JoinType.INNER;
import static io.trino.sql.planner.plan.JoinType.LEFT;
import static io.trino.sql.planner.plan.JoinType.RIGHT;

public class TestPushAggregationThroughOuterJoin
        extends BaseRuleTest
{
    @Test
    public void testPushesAggregationThroughLeftJoin()
    {
        tester().assertThat(new PushAggregationThroughOuterJoin())
                .on(p -> p.aggregation(ab -> ab
                        .source(
                                p.join(
                                        LEFT,
                                        p.values(ImmutableList.of(p.symbol("COL1")), ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 10L)))),
                                        p.values(p.symbol("COL2")),
                                        ImmutableList.of(new EquiJoinClause(p.symbol("COL1"), p.symbol("COL2"))),
                                        ImmutableList.of(p.symbol("COL1")),
                                        ImmutableList.of(p.symbol("COL2")),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                        .addAggregation(p.symbol("AVG", DOUBLE), PlanBuilder.aggregation("avg", ImmutableList.of(new SymbolReference("COL2"))), ImmutableList.of(DOUBLE))
                        .singleGroupingSet(p.symbol("COL1"))))
                .matches(
                        project(ImmutableMap.of(
                                        "COL1", expression(new SymbolReference("COL1")),
                                        "COALESCE", expression(new CoalesceExpression(new SymbolReference("AVG"), new SymbolReference("AVG_NULL")))),
                                join(INNER, builder -> builder
                                        .left(
                                                join(LEFT, leftJoinBuilder -> leftJoinBuilder
                                                        .equiCriteria("COL1", "COL2")
                                                        .left(values(ImmutableMap.of("COL1", 0)))
                                                        .right(aggregation(
                                                                singleGroupingSet("COL2"),
                                                                ImmutableMap.of(Optional.of("AVG"), aggregationFunction("avg", ImmutableList.of("COL2"))),
                                                                Optional.empty(),
                                                                SINGLE,
                                                                values(ImmutableMap.of("COL2", 0))))))
                                        .right(
                                                aggregation(
                                                        globalAggregation(),
                                                        ImmutableMap.of(Optional.of("AVG_NULL"), aggregationFunction("avg", ImmutableList.of("null_literal"))),
                                                        Optional.empty(),
                                                        SINGLE,
                                                        values(ImmutableMap.of("null_literal", 0)))))));
    }

    @Test
    public void testPushesAggregationThroughRightJoin()
    {
        tester().assertThat(new PushAggregationThroughOuterJoin())
                .on(p -> p.aggregation(ab -> ab
                        .source(p.join(
                                RIGHT,
                                p.values(p.symbol("COL2")),
                                p.values(ImmutableList.of(p.symbol("COL1")), ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 10L)))),
                                ImmutableList.of(new EquiJoinClause(p.symbol("COL2"), p.symbol("COL1"))),
                                ImmutableList.of(p.symbol("COL2")),
                                ImmutableList.of(p.symbol("COL1")),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()))
                        .addAggregation(p.symbol("AVG", DOUBLE), PlanBuilder.aggregation("avg", ImmutableList.of(new SymbolReference("COL2"))), ImmutableList.of(DOUBLE))
                        .singleGroupingSet(p.symbol("COL1"))))
                .matches(
                        project(ImmutableMap.of(
                                        "COALESCE", expression(new CoalesceExpression(new SymbolReference("AVG"), new SymbolReference("AVG_NULL"))),
                                        "COL1", expression(new SymbolReference("COL1"))),
                                join(INNER, builder -> builder
                                        .left(
                                                join(RIGHT, leftJoinBuilder -> leftJoinBuilder
                                                        .equiCriteria("COL2", "COL1")
                                                        .left(aggregation(
                                                                singleGroupingSet("COL2"),
                                                                ImmutableMap.of(Optional.of("AVG"), aggregationFunction("avg", ImmutableList.of("COL2"))),
                                                                Optional.empty(),
                                                                SINGLE,
                                                                values(ImmutableMap.of("COL2", 0))))
                                                        .right(values(ImmutableMap.of("COL1", 0)))))
                                        .right(
                                                aggregation(
                                                        globalAggregation(),
                                                        ImmutableMap.of(
                                                                Optional.of("AVG_NULL"), aggregationFunction("avg", ImmutableList.of("null_literal"))),
                                                        Optional.empty(),
                                                        SINGLE,
                                                        values(ImmutableMap.of("null_literal", 0)))))));
    }

    @Test
    public void testPushesAggregationWithMask()
    {
        tester().assertThat(new PushAggregationThroughOuterJoin())
                .on(p -> p.aggregation(ab -> ab
                        .source(
                                p.join(
                                        LEFT,
                                        p.values(ImmutableList.of(p.symbol("COL1")), ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 10L)))),
                                        p.values(p.symbol("COL2"), p.symbol("MASK")),
                                        ImmutableList.of(new EquiJoinClause(p.symbol("COL1"), p.symbol("COL2"))),
                                        ImmutableList.of(p.symbol("COL1")),
                                        ImmutableList.of(p.symbol("COL2"), p.symbol("MASK")),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                        .addAggregation(
                                p.symbol("AVG", DOUBLE),
                                PlanBuilder.aggregation("avg", ImmutableList.of(new SymbolReference("COL2"))),
                                ImmutableList.of(DOUBLE),
                                p.symbol("MASK"))
                        .singleGroupingSet(p.symbol("COL1"))))
                .matches(
                        project(ImmutableMap.of(
                                        "COL1", expression(new SymbolReference("COL1")),
                                        "COALESCE", expression(new CoalesceExpression(new SymbolReference("AVG"), new SymbolReference("AVG_NULL")))),
                                join(INNER, builder -> builder
                                        .left(
                                                join(LEFT, leftJoinBuilder -> leftJoinBuilder
                                                        .equiCriteria("COL1", "COL2")
                                                        .left(values(ImmutableMap.of("COL1", 0)))
                                                        .right(
                                                                aggregation(
                                                                        singleGroupingSet("COL2"),
                                                                        ImmutableMap.of(Optional.of("AVG"), aggregationFunction("avg", ImmutableList.of("COL2"))),
                                                                        ImmutableList.of(),
                                                                        ImmutableList.of("MASK"),
                                                                        Optional.empty(),
                                                                        SINGLE,
                                                                        values(ImmutableMap.of("COL2", 0, "MASK", 1))))))
                                        .right(
                                                aggregation(
                                                        globalAggregation(),
                                                        ImmutableMap.of(Optional.of("AVG_NULL"), aggregationFunction("avg", ImmutableList.of("null_literal"))),
                                                        ImmutableList.of(),
                                                        ImmutableList.of("MASK_NULL"),
                                                        Optional.empty(),
                                                        SINGLE,
                                                        values(ImmutableMap.of("null_literal", 0, "MASK_NULL", 1)))))));
    }

    @Test
    public void testPushCountAllAggregation()
    {
        tester().assertThat(new PushAggregationThroughOuterJoin())
                .on(p -> p.aggregation(ab -> ab
                        .source(
                                p.join(
                                        LEFT,
                                        p.values(ImmutableList.of(p.symbol("COL1")), ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 10L)))),
                                        p.values(p.symbol("COL2")),
                                        ImmutableList.of(new EquiJoinClause(p.symbol("COL1"), p.symbol("COL2"))),
                                        ImmutableList.of(p.symbol("COL1")),
                                        ImmutableList.of(p.symbol("COL2")),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                        .addAggregation(p.symbol("COUNT"), PlanBuilder.aggregation("count", ImmutableList.of()), ImmutableList.of())
                        .singleGroupingSet(p.symbol("COL1"))))
                .matches(
                        project(ImmutableMap.of(
                                        "COL1", expression(new SymbolReference("COL1")),
                                        "COALESCE", expression(new CoalesceExpression(new SymbolReference("COUNT"), new SymbolReference("COUNT_NULL")))),
                                join(INNER, builder -> builder
                                        .left(
                                                join(LEFT, leftJoinBuilder -> leftJoinBuilder
                                                        .equiCriteria("COL1", "COL2")
                                                        .left(values(ImmutableMap.of("COL1", 0)))
                                                        .right(
                                                                aggregation(
                                                                        singleGroupingSet("COL2"),
                                                                        ImmutableMap.of(Optional.of("COUNT"), aggregationFunction("count", ImmutableList.of())),
                                                                        Optional.empty(),
                                                                        SINGLE,
                                                                        values(ImmutableMap.of("COL2", 0))))))
                                        .right(
                                                aggregation(
                                                        globalAggregation(),
                                                        ImmutableMap.of(Optional.of("COUNT_NULL"), aggregationFunction("count", ImmutableList.of())),
                                                        Optional.empty(),
                                                        SINGLE,
                                                        values(ImmutableMap.of("null_literal", 0)))))));
    }

    @Test
    public void testDoesNotFireWhenMultipleGroupingSets()
    {
        tester().assertThat(new PushAggregationThroughOuterJoin())
                .on(p -> p.aggregation(ab -> ab
                        .source(
                                p.join(
                                        LEFT,
                                        p.values(
                                                ImmutableList.of(p.symbol("COL1"), p.symbol("COL2")),
                                                ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 1L), new Constant(INTEGER, 2L)))),
                                        p.values(
                                                ImmutableList.of(p.symbol("COL3")),
                                                ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 1L)))),
                                        ImmutableList.of(new EquiJoinClause(p.symbol("COL1"), p.symbol("COL3"))),
                                        ImmutableList.of(p.symbol("COL1")),
                                        ImmutableList.of(p.symbol("COL3")),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                        .addAggregation(p.symbol("COUNT"), PlanBuilder.aggregation("count", ImmutableList.of()), ImmutableList.of())
                        .groupingSets(groupingSets(ImmutableList.of(p.symbol("COL1"), p.symbol("COL2")), 2, ImmutableSet.of()))))
                .doesNotFire();
    }

    @Test
    public void testDoesNotFireWhenNotDistinct()
    {
        tester().assertThat(new PushAggregationThroughOuterJoin())
                .on(p -> p.aggregation(ab -> ab
                        .source(p.join(
                                LEFT,
                                p.values(ImmutableList.of(p.symbol("COL1")), ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 10L)), ImmutableList.of(new Constant(INTEGER, 11L)))),
                                p.values(new Symbol("COL2")),
                                ImmutableList.of(new EquiJoinClause(new Symbol("COL1"), new Symbol("COL2"))),
                                ImmutableList.of(p.symbol("COL1")),
                                ImmutableList.of(p.symbol("COL2")),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()))
                        .addAggregation(new Symbol("AVG"), PlanBuilder.aggregation("avg", ImmutableList.of(new SymbolReference("COL2"))), ImmutableList.of(DOUBLE))
                        .singleGroupingSet(new Symbol("COL1"))))
                .doesNotFire();

        // https://github.com/prestodb/presto/issues/10592
        tester().assertThat(new PushAggregationThroughOuterJoin())
                .on(p -> p.aggregation(ab -> ab
                        .source(
                                p.join(
                                        LEFT,
                                        p.project(Assignments.builder()
                                                        .putIdentity(p.symbol("COL1", BIGINT))
                                                        .build(),
                                                p.aggregation(builder ->
                                                        builder.singleGroupingSet(p.symbol("COL1"), p.symbol("unused"))
                                                                .source(
                                                                        p.values(
                                                                                ImmutableList.of(p.symbol("COL1"), p.symbol("unused")),
                                                                                ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 10L), new Constant(INTEGER, 1L)), ImmutableList.of(new Constant(INTEGER, 10L), new Constant(INTEGER, 2L))))))),
                                        p.values(p.symbol("COL2")),
                                        ImmutableList.of(new EquiJoinClause(p.symbol("COL1"), p.symbol("COL2"))),
                                        ImmutableList.of(p.symbol("COL1")),
                                        ImmutableList.of(p.symbol("COL2")),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                        .addAggregation(p.symbol("AVG", DOUBLE), PlanBuilder.aggregation("avg", ImmutableList.of(new SymbolReference("COL2"))), ImmutableList.of(DOUBLE))
                        .singleGroupingSet(p.symbol("COL1"))))
                .doesNotFire();
    }

    @Test
    public void testDoesNotFireWhenGroupingOnInner()
    {
        tester().assertThat(new PushAggregationThroughOuterJoin())
                .on(p -> p.aggregation(ab -> ab
                        .source(p.join(LEFT,
                                p.values(ImmutableList.of(p.symbol("COL1")), ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 10L)))),
                                p.values(new Symbol("COL2"), new Symbol("COL3")),
                                ImmutableList.of(new EquiJoinClause(new Symbol("COL1"), new Symbol("COL2"))),
                                ImmutableList.of(p.symbol("COL1")),
                                ImmutableList.of(p.symbol("COL2")),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()))
                        .addAggregation(new Symbol("AVG"), PlanBuilder.aggregation("avg", ImmutableList.of(new SymbolReference("COL2"))), ImmutableList.of(DOUBLE))
                        .singleGroupingSet(new Symbol("COL1"), new Symbol("COL3"))))
                .doesNotFire();
    }

    @Test
    public void testDoesNotFireWhenAggregationDoesNotHaveSymbols()
    {
        tester().assertThat(new PushAggregationThroughOuterJoin())
                .on(p -> p.aggregation(ab -> ab
                        .source(p.join(
                                LEFT,
                                p.values(ImmutableList.of(p.symbol("COL1")), ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 10L)))),
                                p.values(ImmutableList.of(p.symbol("COL2")), ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 20L)))),
                                ImmutableList.of(new EquiJoinClause(new Symbol("COL1"), new Symbol("COL2"))),
                                ImmutableList.of(p.symbol("COL1")),
                                ImmutableList.of(p.symbol("COL2")),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()))
                        .addAggregation(new Symbol("SUM"), PlanBuilder.aggregation("sum", ImmutableList.of(new SymbolReference("COL1"))), ImmutableList.of(DOUBLE))
                        .singleGroupingSet(new Symbol("COL1"))))
                .doesNotFire();
    }

    @Test
    public void testDoesNotFireWhenAggregationOnMultipleSymbolsDoesNotHaveSomeSymbols()
    {
        tester().assertThat(new PushAggregationThroughOuterJoin())
                .on(p -> p.aggregation(ab -> ab
                        .source(p.join(
                                LEFT,
                                p.values(ImmutableList.of(p.symbol("COL1")), ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 10L)))),
                                p.values(ImmutableList.of(p.symbol("COL2"), p.symbol("COL3")), ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 20L), new Constant(INTEGER, 30L)))),
                                ImmutableList.of(new EquiJoinClause(new Symbol("COL1"), new Symbol("COL2"))),
                                ImmutableList.of(new Symbol("COL1")),
                                ImmutableList.of(new Symbol("COL2")),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()))
                        .addAggregation(new Symbol("MIN_BY"), PlanBuilder.aggregation("min_by", ImmutableList.of(new SymbolReference("COL2"), new SymbolReference("COL1"))), ImmutableList.of(DOUBLE, DOUBLE))
                        .singleGroupingSet(new Symbol("COL1"))))
                .doesNotFire();

        tester().assertThat(new PushAggregationThroughOuterJoin())
                .on(p -> p.aggregation(ab -> ab
                        .source(p.join(
                                LEFT,
                                p.values(ImmutableList.of(p.symbol("COL1")), ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 10L)))),
                                p.values(ImmutableList.of(p.symbol("COL2"), p.symbol("COL3")), ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 20L), new Constant(INTEGER, 30L)))),
                                ImmutableList.of(new EquiJoinClause(new Symbol("COL1"), new Symbol("COL2"))),
                                ImmutableList.of(new Symbol("COL1")),
                                ImmutableList.of(new Symbol("COL2")),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()))
                        .addAggregation(new Symbol("SUM"), PlanBuilder.aggregation("sum", ImmutableList.of(new SymbolReference("COL2"))), ImmutableList.of(DOUBLE))
                        .addAggregation(new Symbol("MIN_BY"), PlanBuilder.aggregation("min_by", ImmutableList.of(new SymbolReference("COL2"), new SymbolReference("COL3"))), ImmutableList.of(DOUBLE, DOUBLE))
                        .addAggregation(new Symbol("MAX_BY"), PlanBuilder.aggregation("max_by", ImmutableList.of(new SymbolReference("COL2"), new SymbolReference("COL1"))), ImmutableList.of(DOUBLE, DOUBLE))
                        .singleGroupingSet(new Symbol("COL1"))))
                .doesNotFire();
    }
}
