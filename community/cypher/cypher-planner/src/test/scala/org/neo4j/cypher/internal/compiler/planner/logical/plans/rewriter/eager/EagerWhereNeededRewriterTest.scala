/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.planner.logical.plans.rewriter.eager

import org.neo4j.cypher.internal.ast.AstConstructionTestSupport
import org.neo4j.cypher.internal.compiler.helpers.LogicalPlanBuilder
import org.neo4j.cypher.internal.compiler.planner.LogicalPlanTestOps
import org.neo4j.cypher.internal.expressions.functions.Head
import org.neo4j.cypher.internal.ir.EagernessReason
import org.neo4j.cypher.internal.ir.EagernessReason.Conflict
import org.neo4j.cypher.internal.ir.EagernessReason.LabelReadRemoveConflict
import org.neo4j.cypher.internal.ir.EagernessReason.LabelReadSetConflict
import org.neo4j.cypher.internal.ir.EagernessReason.PropertyReadSetConflict
import org.neo4j.cypher.internal.ir.EagernessReason.ReadCreateConflict
import org.neo4j.cypher.internal.ir.EagernessReason.ReadDeleteConflict
import org.neo4j.cypher.internal.ir.EagernessReason.UnknownPropertyReadSetConflict
import org.neo4j.cypher.internal.ir.RemoveLabelPattern
import org.neo4j.cypher.internal.logical.builder.AbstractLogicalPlanBuilder.createNode
import org.neo4j.cypher.internal.logical.builder.AbstractLogicalPlanBuilder.createNodeWithProperties
import org.neo4j.cypher.internal.logical.builder.AbstractLogicalPlanBuilder.createPattern
import org.neo4j.cypher.internal.logical.builder.AbstractLogicalPlanBuilder.createRelationship
import org.neo4j.cypher.internal.logical.builder.AbstractLogicalPlanBuilder.setNodeProperties
import org.neo4j.cypher.internal.logical.builder.AbstractLogicalPlanBuilder.setNodePropertiesFromMap
import org.neo4j.cypher.internal.logical.builder.AbstractLogicalPlanBuilder.setNodeProperty
import org.neo4j.cypher.internal.logical.plans.Ascending
import org.neo4j.cypher.internal.logical.plans.NestedPlanCollectExpression
import org.neo4j.cypher.internal.logical.plans.NestedPlanExistsExpression
import org.neo4j.cypher.internal.util.attribution.Attributes
import org.neo4j.cypher.internal.util.attribution.Id
import org.neo4j.cypher.internal.util.attribution.IdGen
import org.neo4j.cypher.internal.util.attribution.SequentialIdGen
import org.neo4j.cypher.internal.util.symbols.CTAny
import org.neo4j.cypher.internal.util.symbols.CTList
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite
import org.neo4j.graphdb.schema.IndexType

import scala.collection.immutable.ListSet

class EagerWhereNeededRewriterTest extends CypherFunSuite with LogicalPlanTestOps with AstConstructionTestSupport {

  /**
   * Get a builder for incomplete logical plans with an ID offset.
   * This can be used to build plans for nested plan expressions.
   *
   * The ID offset helps to avoid having ID conflicts in the outer plan that
   * contains the nested plan.
   */
  private def subPlanBuilderWithIdOffset(): LogicalPlanBuilder =
    new LogicalPlanBuilder(wholePlan = false) {
      override val idGen: IdGen = new SequentialIdGen(100)
    }

  // Negative tests

  test("inserts no eager in linear read query") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("n")
      .limit(5)
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts no eager in branched read query") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("n")
      .limit(5)
      .apply()
      .|.filter("n.prop > 5")
      .|.argument("n")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts no eager if a unary EagerLogicalPlan is already present where needed") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .projection("n.prop AS foo")
      .sort(Seq(Ascending("n")))
      .setNodeProperty("n", "prop", "5")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts no eager if a binary EagerLogicalPlan is already present where needed. Conflict LHS with RHS.") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .nodeHashJoin("n")
      .|.projection("n.prop AS foo")
      .|.allNodeScan("n")
      .setNodeProperty("n", "prop", "5")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts no eager if a binary EagerLogicalPlan is already present where needed. Conflict LHS with Top.") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .projection("n.prop AS foo")
      .nodeHashJoin("n")
      .|.allNodeScan("n")
      .setNodeProperty("n", "prop", "5")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts no eager if there is a conflict between LHS and RHS of a Union.") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .union()
      .|.projection("n.prop AS foo")
      .|.allNodeScan("n")
      .setNodeProperty("n", "prop", "5")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("does not support if there is a conflict between LHS and RHS of an OrderedUnion.") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .orderedUnion(Seq(Ascending("n")))
      .|.projection("n.prop AS foo")
      .|.allNodeScan("n")
      .setNodeProperty("n", "prop", "5")
      .allNodeScan("n")
    val plan = planBuilder.build()

    // Currently OrderedUnion is only planned for read-plans
    an[IllegalStateException] should be thrownBy {
      EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
        plan,
        planBuilder.getSemanticTable
      )
    }
  }

  test("does not support if there is a conflict between LHS and RHS of an AssertSameNode.") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .assertSameNode("n")
      .|.projection("n.prop AS foo")
      .|.allNodeScan("n")
      .setNodeProperty("n", "prop", "5")
      .allNodeScan("n")
    val plan = planBuilder.build()

    // Currently AssertSameNode is only planned for read-plans
    an[IllegalStateException] should be thrownBy {
      EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
        plan,
        planBuilder.getSemanticTable
      )
    }
  }

  // Property Read/Set conflicts

  test("inserts eager between property set and property read of same property on same node") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .projection("n.prop AS foo")
      .setNodeProperty("n", "prop", "5")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .projection("n.prop AS foo")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(2), Id(1))))))
        .setNodeProperty("n", "prop", "5")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts no eager between property set and property read of different property on same node") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .projection("n.prop2 AS foo")
      .setNodeProperty("n", "prop", "5")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "inserts eager between property set and property read of same property when read appears in second set property"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .setNodeProperty("n", "prop", "n.prop + 1")
      .setNodeProperty("n", "prop", "5")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .setNodeProperty("n", "prop", "n.prop + 1")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(3), Id(2))))))
        .setNodeProperty("n", "prop", "5")
        .allNodeScan("n")
        .build()
    )
  }

  test("insert eager for multiple properties set") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("nv1", "nv2")
      .projection("n.v1 AS nv1", "n.v2 AS nv2")
      .setNodeProperties("n", ("v1", "n.v1 + 1"), ("v2", "n.v2 + 1"))
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("nv1", "nv2")
        .projection("n.v1 AS nv1", "n.v2 AS nv2")
        .eager(ListSet(
          PropertyReadSetConflict(propName("v2"), Some(Conflict(Id(2), Id(1)))),
          PropertyReadSetConflict(propName("v1"), Some(Conflict(Id(2), Id(1))))
        ))
        .setNodeProperties("n", ("v1", "n.v1 + 1"), ("v2", "n.v2 + 1"))
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts no eager between property set and property read (NodeIndexScan) if property read through stable iterator"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .setNodeProperty("m", "prop", "5")
      .apply()
      .|.allNodeScan("m")
      .nodeIndexOperator("n:N(prop)")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "inserts eager between property set and property read (NodeIndexScan) if property read through unstable iterator"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .setNodeProperty("m", "prop", "5")
      .apply()
      .|.nodeIndexOperator("n:N(prop)")
      .allNodeScan("m")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .setNodeProperty("m", "prop", "5")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(2), Id(4))))))
        .apply()
        .|.nodeIndexOperator("n:N(prop)")
        .allNodeScan("m")
        .build()
    )
  }

  test("inserts eager between property set and all properties read") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("properties")
      .projection("properties(n) AS properties")
      .setNodeProperty("m", "prop", "42")
      .expand("(n)-[r]->(m)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("properties")
        .projection("properties(n) AS properties")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(2), Id(1))))))
        .setNodeProperty("m", "prop", "42")
        .expand("(n)-[r]->(m)")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager when setting concrete properties from map") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("p")
      .projection("m.prop AS p")
      .setNodePropertiesFromMap("m", "{prop: 42}", removeOtherProps = false)
      .expand("(n)-[r]->(m)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("p")
        .projection("m.prop AS p")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(2), Id(1))))))
        .setNodePropertiesFromMap("m", "{prop: 42}", removeOtherProps = false)
        .expand("(n)-[r]->(m)")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts no eager when setting concrete different properties from map") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("p")
      .projection("m.prop AS p")
      .setNodePropertiesFromMap("m", "{prop2: 42}", removeOtherProps = false)
      .expand("(n)-[r]->(m)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts eager when setting concrete different properties from map but removing previous properties") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("p")
      .projection("m.prop AS p")
      .setNodePropertiesFromMap("m", "{prop2: 42}", removeOtherProps = true)
      .expand("(n)-[r]->(m)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("p")
        .projection("m.prop AS p")
        .eager(ListSet(UnknownPropertyReadSetConflict(Some(Conflict(Id(2), Id(1))))))
        .setNodePropertiesFromMap("m", "{prop2: 42}", removeOtherProps = true)
        .expand("(n)-[r]->(m)")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager when setting unknown properties from map") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("p")
      .projection("m.prop AS p")
      .setNodePropertiesFromMap("m", "$param", removeOtherProps = false)
      .expand("(n)-[r]->(m)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("p")
        .projection("m.prop AS p")
        .eager(ListSet(UnknownPropertyReadSetConflict(Some(Conflict(Id(2), Id(1))))))
        .setNodePropertiesFromMap("m", "$param", removeOtherProps = false)
        .expand("(n)-[r]->(m)")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager between property set and property read in nested plan expression") {
    val nestedPlan = subPlanBuilderWithIdOffset()
      .filter("m.prop > 0")
      .expand("(n)-[r]->(m)")
      .argument("n")
      .build()

    val nestedPlanExpression = NestedPlanExistsExpression(
      nestedPlan,
      s"EXISTS { MATCH (n)-[r]->(m) WHERE m.prop > 0 }"
    )(pos)

    val planBuilder = new LogicalPlanBuilder()
      .produceResults("x")
      .projection("n.foo AS x")
      .filterExpression(nestedPlanExpression)
      .setNodeProperty("n", "prop", "5")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("x")
        .projection("n.foo AS x")
        .filterExpression(nestedPlanExpression)
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(3), Id(2))))))
        .setNodeProperty("n", "prop", "5")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager between property set and all properties read in nested plan expression") {
    val nestedPlan = subPlanBuilderWithIdOffset()
      .projection("properties(m) AS properties")
      .expand("(n)-[r]->(m)")
      .argument("n")
      .build()

    val nestedPlanExpression = NestedPlanCollectExpression(
      nestedPlan,
      varFor("properties"),
      s"COLLECT { MATCH (n)-[r]->(m) RETURN properties(m) AS properties }"
    )(pos)

    val planBuilder = new LogicalPlanBuilder()
      .produceResults("ps")
      .projection(Map("ps" -> nestedPlanExpression))
      .setNodeProperty("n", "prop", "5")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("ps")
        .projection(Map("ps" -> nestedPlanExpression))
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(2), Id(1))))))
        .setNodeProperty("n", "prop", "5")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager between property set and property read in projection of nested plan collect expression") {
    val nestedPlan = subPlanBuilderWithIdOffset()
      .expand("(n)-[r]->(m)")
      .argument("n")
      .build()

    val nestedPlanExpression = NestedPlanCollectExpression(
      nestedPlan,
      prop("m", "prop"),
      s"[(n)-[r]->(m) | m.prop]"
    )(pos)

    val planBuilder = new LogicalPlanBuilder()
      .produceResults("mProps")
      .projection(Map("mProps" -> nestedPlanExpression))
      .setNodeProperty("n", "prop", "5")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("mProps")
        .projection(Map("mProps" -> nestedPlanExpression))
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(2), Id(1))))))
        .setNodeProperty("n", "prop", "5")
        .allNodeScan("n")
        .build()
    )
  }

  // Label Read/Set conflict

  test("inserts no eager between label set and label read if label read through stable iterator") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .setLabels("m", "N")
      .apply()
      .|.allNodeScan("m")
      .nodeByLabelScan("n", "N")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts eager between label set and NodeByIdSeek with label filter if read through stable iterator") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .setLabels("m", "N")
      .apply()
      .|.allNodeScan("m")
      .filter("n:N")
      .nodeByIdSeek("n", Set.empty, 1)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .setLabels("m", "N")
        .apply()
        .|.allNodeScan("m")
        .eager(ListSet(LabelReadSetConflict(labelName("N"), Some(Conflict(Id(2), Id(5))))))
        .filter("n:N")
        .nodeByIdSeek("n", Set.empty, 1)
        .build()
    )
  }

  test("inserts eager between label remove and NodeByIdSeek with label filter if read through stable iterator") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .removeLabels("m", "N")
      .apply()
      .|.allNodeScan("m")
      .filter("n:N")
      .nodeByIdSeek("n", Set.empty, 1)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .removeLabels("m", "N")
        .apply()
        .|.allNodeScan("m")
        .eager(ListSet(LabelReadRemoveConflict(labelName("N"), Some(Conflict(Id(2), Id(5))))))
        .filter("n:N")
        .nodeByIdSeek("n", Set.empty, 1)
        .build()
    )
  }

  test("inserts eager between label set and label read (NodeByLabelScan) if label read through unstable iterator") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .setLabels("m", "N")
      .apply()
      .|.nodeByLabelScan("n", "N")
      .allNodeScan("m")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .setLabels("m", "N")
        .eager(ListSet(LabelReadSetConflict(labelName("N"), Some(Conflict(Id(2), Id(4))))))
        .apply()
        .|.nodeByLabelScan("n", "N")
        .allNodeScan("m")
        .build()
    )
  }

  test(
    "inserts eager between label set and label read (UnionNodeByLabelScan) if label read through unstable iterator"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .setLabels("m", "N")
      .apply()
      .|.unionNodeByLabelsScan("n", Seq("N", "M"))
      .allNodeScan("m")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .setLabels("m", "N")
        .eager(ListSet(LabelReadSetConflict(labelName("N"), Some(Conflict(Id(2), Id(4))))))
        .apply()
        .|.unionNodeByLabelsScan("n", Seq("N", "M"))
        .allNodeScan("m")
        .build()
    )
  }

  test("inserts eager between label set and label read (NodeIndexScan) if label read through unstable iterator") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .setLabels("m", "N")
      .apply()
      .|.nodeIndexOperator("n:N(prop)")
      .allNodeScan("m")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .setLabels("m", "N")
        .eager(ListSet(LabelReadSetConflict(labelName("N"), Some(Conflict(Id(2), Id(4))))))
        .apply()
        .|.nodeIndexOperator("n:N(prop)")
        .allNodeScan("m")
        .build()
    )
  }

  test("inserts eager between label set and all labels read") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("labels")
      .projection("labels(n) AS labels")
      .setLabels("m", "A")
      .expand("(n)-[r]->(m)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("labels")
        .projection("labels(n) AS labels")
        .eager(ListSet(LabelReadSetConflict(labelName("A"), Some(Conflict(Id(2), Id(1))))))
        .setLabels("m", "A")
        .expand("(n)-[r]->(m)")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager between label set and negated label read") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("labels")
      .setLabels("m", "A")
      .expand("(n)-[r]->(m)")
      .cartesianProduct()
      .|.filter("n:!A")
      .|.allNodeScan("n")
      .argument()
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("labels")
        .setLabels("m", "A")
        .expand("(n)-[r]->(m)")
        .eager(ListSet(LabelReadSetConflict(labelName("A"), Some(Conflict(Id(1), Id(4))))))
        .cartesianProduct()
        .|.filter("n:!A")
        .|.allNodeScan("n")
        .argument()
        .build()
    )
  }

  test(
    "inserts eager between label set and label read (NodeCountFromCountStore) if label read through unstable iterator"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .setLabels("m", "N")
      .apply()
      .|.nodeCountFromCountStore("count", Seq(Some("N")))
      .allNodeScan("m")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .setLabels("m", "N")
        .eager(ListSet(LabelReadSetConflict(labelName("N"), Some(Conflict(Id(2), Id(4))))))
        .apply()
        .|.nodeCountFromCountStore("count", Seq(Some("N")))
        .allNodeScan("m")
        .build()
    )
  }

  test(
    "inserts no eager between label set and label read (NodeCountFromCountStore) if label read through stable iterator"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .setLabels("m", "N")
      .apply()
      .|.allNodeScan("m")
      .nodeCountFromCountStore("count", Seq(Some("N")))
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts eager between label set and label read in nested plan expression") {
    val nestedPlan = subPlanBuilderWithIdOffset()
      .filter("m:N")
      .expand("(n)-[r]->(m)")
      .argument("n")
      .build()

    val nestedPlanExpression = NestedPlanExistsExpression(
      nestedPlan,
      s"EXISTS { MATCH (n)-[r]->(m:N) }"
    )(pos)

    val planBuilder = new LogicalPlanBuilder()
      .produceResults("x")
      .projection("n.foo AS x")
      .filterExpression(nestedPlanExpression)
      .setLabels("n", "N")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("x")
        .projection("n.foo AS x")
        .filterExpression(nestedPlanExpression)
        .eager(ListSet(LabelReadSetConflict(labelName("N"), Some(Conflict(Id(3), Id(2))))))
        .setLabels("n", "N")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager between label set and all labels read in nested plan expression") {
    val nestedPlan = subPlanBuilderWithIdOffset()
      .projection("labels(m) AS labels")
      .expand("(n)-[r]->(m)")
      .argument("n")
      .build()

    val nestedPlanExpression = NestedPlanCollectExpression(
      nestedPlan,
      varFor("labels"),
      s"COLLECT { MATCH (n)-[r]->(m) RETURN labels(m) AS labels }"
    )(pos)

    val planBuilder = new LogicalPlanBuilder()
      .produceResults("lbs")
      .projection(Map("lbs" -> nestedPlanExpression))
      .setLabels("n", "N")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("lbs")
        .projection(Map("lbs" -> nestedPlanExpression))
        .eager(ListSet(LabelReadSetConflict(labelName("N"), Some(Conflict(Id(2), Id(1))))))
        .setLabels("n", "N")
        .allNodeScan("n")
        .build()
    )
  }

  // Read vs Create conflicts

  test("inserts no eager between Create and AllNodeScan if read through stable iterator") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("m"))
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts no eager between label create and label read (Filter) after stable AllNodeScan") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("m", "N", "O"))
      .filter("n:N")
      .filter("n:O")
      .unwind("n.prop AS prop")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts eager between Create and AllNodeScan if read through unstable iterator") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o"))
      .cartesianProduct()
      .|.allNodeScan("m")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNode("o"))
        .eager(ListSet(ReadCreateConflict(Some(Conflict(Id(1), Id(3))))))
        .cartesianProduct()
        .|.allNodeScan("m")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager between Create with label and AllNodeScan if read through unstable iterator") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "O"))
      .cartesianProduct()
      .|.allNodeScan("m")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNode("o", "O"))
        .eager(ListSet(LabelReadSetConflict(labelName("O"), Some(Conflict(Id(1), Id(3))))))
        .cartesianProduct()
        .|.allNodeScan("m")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts eager between Create and All nodes read (NodeCountFromCountStore) if read through unstable iterator"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("o", "O"))
      .apply()
      .|.nodeCountFromCountStore("count", Seq(None))
      .nodeByLabelScan("m", "M")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("m")
        .create(createNode("o", "O"))
        .eager(ListSet(LabelReadSetConflict(labelName("O"), Some(Conflict(Id(1), Id(3))))))
        .apply()
        .|.nodeCountFromCountStore("count", Seq(None))
        .nodeByLabelScan("m", "M")
        .build()
    )
  }

  test(
    "inserts no eager between Create and All nodes read (NodeCountFromCountStore) if read through stable iterator"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("o", "O"))
      .apply()
      .|.nodeByLabelScan("m", "M")
      .nodeCountFromCountStore("count", Seq(None))
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "inserts eager between Create and label read (NodeCountFromCountStore) if label read through unstable iterator"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("o", "N"))
      .apply()
      .|.nodeCountFromCountStore("count", Seq(Some("N")))
      .allNodeScan("m")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("m")
        .create(createNode("o", "N"))
        .eager(ListSet(LabelReadSetConflict(labelName("N"), Some(Conflict(Id(1), Id(3))))))
        .apply()
        .|.nodeCountFromCountStore("count", Seq(Some("N")))
        .allNodeScan("m")
        .build()
    )
  }

  test("inserts no eager between bare Create and NodeByLabelScan") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o"))
      .cartesianProduct()
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts no eager between Create and NodeByLabelScan if no label overlap") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "O"))
      .cartesianProduct()
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts eager between Create and NodeByLabelScan if label overlap") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "M"))
      .cartesianProduct()
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNode("o", "M"))
        .eager(ListSet(LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(3))))))
        .cartesianProduct()
        .|.nodeByLabelScan("m", "M")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts no eager between Create and UnionNodeByLabelScan if no label overlap"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("o", "O"))
      .apply()
      .|.unionNodeByLabelsScan("n", Seq("N", "M"))
      .allNodeScan("m")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "inserts eager between Create and UnionNodeByLabelScan if label overlap"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("o", "M"))
      .apply()
      .|.unionNodeByLabelsScan("n", Seq("N", "M"))
      .allNodeScan("m")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("m")
        .create(createNode("o", "M"))
        .eager(ListSet(LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(3))))))
        .apply()
        .|.unionNodeByLabelsScan("n", Seq("N", "M"))
        .allNodeScan("m")
        .build()
    )
  }

  test(
    "inserts no eager between Create and IntersectionNodeByLabelScan if no label overlap"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("o", "O"))
      .apply()
      .|.unionNodeByLabelsScan("n", Seq("N", "M"))
      .allNodeScan("m")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "inserts eager between Create and IntersectionNodeByLabelScan if label overlap"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("o", "M", "N"))
      .apply()
      .|.intersectionNodeByLabelsScan("n", Seq("N", "M"))
      .allNodeScan("m")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("m")
        .create(createNode("o", "M", "N"))
        .eager(ListSet(
          LabelReadSetConflict(labelName("N"), Some(Conflict(Id(1), Id(3)))),
          LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(3))))
        ))
        .apply()
        .|.intersectionNodeByLabelsScan("n", Seq("N", "M"))
        .allNodeScan("m")
        .build()
    )
  }

  test(
    "inserts no eager between Create and NodeByLabelScan if no label overlap with ANDed labels, same label in Filter"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "O"))
      .cartesianProduct()
      .|.filter("m:O")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "inserts eager between Create and NodeByLabelScan if label overlap with ANDed labels"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "O", "M"))
      .cartesianProduct()
      .|.filter("m:O")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNode("o", "O", "M"))
        .eager(ListSet(
          LabelReadSetConflict(labelName("O"), Some(Conflict(Id(1), Id(4)))),
          LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(4))))
        ))
        .cartesianProduct()
        .|.filter("m:O")
        .|.nodeByLabelScan("m", "M")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts no eager between Create and NodeByLabelScan if no label overlap with ORed labels in union"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "P"))
      .cartesianProduct()
      .|.distinct("m AS m")
      .|.union()
      .|.|.nodeByLabelScan("m", "O")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "inserts eager between Create and NodeByLabelScan if label overlap with ORed labels in union"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "O"))
      .cartesianProduct()
      .|.distinct("m AS m")
      .|.union()
      .|.|.nodeByLabelScan("m", "O")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNode("o", "O"))
        .eager(ListSet(
          LabelReadSetConflict(labelName("O"), Some(Conflict(Id(1), Id(5)))),
          LabelReadSetConflict(labelName("O"), Some(Conflict(Id(1), Id(6))))
        ))
        .cartesianProduct()
        .|.distinct("m AS m")
        .|.union()
        .|.|.nodeByLabelScan("m", "O")
        .|.nodeByLabelScan("m", "M")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts no eager between Create and NodeByLabelScan if no label overlap with ANDed labels in join"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "O"))
      .cartesianProduct()
      .|.nodeHashJoin("m")
      .|.|.nodeByLabelScan("m", "O")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "inserts eager between Create and NodeByLabelScan if label overlap with ANDed labels in join"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "O", "M"))
      .cartesianProduct()
      .|.nodeHashJoin("m")
      .|.|.nodeByLabelScan("m", "O")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNode("o", "O", "M"))
        .eager(ListSet(
          LabelReadSetConflict(labelName("O"), Some(Conflict(Id(1), Id(4)))),
          LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(4)))),
          LabelReadSetConflict(labelName("O"), Some(Conflict(Id(1), Id(5)))),
          LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(5))))
        ))
        .cartesianProduct()
        .|.nodeHashJoin("m")
        .|.|.nodeByLabelScan("m", "O")
        .|.nodeByLabelScan("m", "M")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts eager between Create and NodeByLabelScan if label overlap with ANDed labels in AssertSameNode"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "O", "M"))
      .cartesianProduct()
      .|.assertSameNode("m")
      .|.|.nodeByLabelScan("m", "O")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNode("o", "O", "M"))
        .eager(ListSet(
          LabelReadSetConflict(labelName("O"), Some(Conflict(Id(1), Id(4)))),
          LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(4)))),
          LabelReadSetConflict(labelName("O"), Some(Conflict(Id(1), Id(5)))),
          LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(5))))
        ))
        .cartesianProduct()
        .|.assertSameNode("m")
        .|.|.nodeByLabelScan("m", "O")
        .|.nodeByLabelScan("m", "M")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts eager between Create and NodeByLabelScan if label overlap with labels of LHS of left outer join"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "M"))
      .cartesianProduct()
      .|.leftOuterHashJoin("m")
      .|.|.nodeByLabelScan("m", "O")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNode("o", "M"))
        .eager(ListSet(
          LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(4)))),
          LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(5))))
        ))
        .cartesianProduct()
        .|.leftOuterHashJoin("m")
        .|.|.nodeByLabelScan("m", "O")
        .|.nodeByLabelScan("m", "M")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts eager between Create and NodeByLabelScan if label overlap with labels of RHS of right outer join"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "O"))
      .cartesianProduct()
      .|.rightOuterHashJoin("m")
      .|.|.nodeByLabelScan("m", "O")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNode("o", "O"))
        .eager(ListSet(
          LabelReadSetConflict(labelName("O"), Some(Conflict(Id(1), Id(4)))),
          LabelReadSetConflict(labelName("O"), Some(Conflict(Id(1), Id(5))))
        ))
        .cartesianProduct()
        .|.rightOuterHashJoin("m")
        .|.|.nodeByLabelScan("m", "O")
        .|.nodeByLabelScan("m", "M")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "does not insert eager between Create and NodeByLabelScan if label overlap with labels of RHS of left outer join"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "O"))
      .cartesianProduct()
      .|.leftOuterHashJoin("m")
      .|.|.nodeByLabelScan("m", "O")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "does not insert eager between Create and NodeByLabelScan if label overlap with labels of LHS of right outer join"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "M"))
      .cartesianProduct()
      .|.rightOuterHashJoin("m")
      .|.|.nodeByLabelScan("m", "O")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "does not insert eager between Create and NodeByLabelScan if label overlap with optional labels"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "O"))
      .cartesianProduct()
      .|.apply()
      .|.|.optional()
      .|.|.filter("m:O")
      .|.|.argument("m")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "inserts eager between Create and NodeByLabelScan if label overlap with non-optional labels"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "M"))
      .cartesianProduct()
      .|.apply()
      .|.|.optional()
      .|.|.filter("m:O")
      .|.|.argument("m")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNode("o", "M"))
        .eager(ListSet(LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(7))))))
        .cartesianProduct()
        .|.apply()
        .|.|.optional()
        .|.|.filter("m:O")
        .|.|.argument("m")
        .|.nodeByLabelScan("m", "M")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "does not insert eager between Create and NodeByLabelScan if label overlap with labels from SemiApply"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "O"))
      .cartesianProduct()
      .|.semiApply()
      .|.|.filter("m:O")
      .|.|.argument("m")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "does not insert eager between Create and NodeByLabelScan if label overlap with labels outside of SemiApply"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "M"))
      .cartesianProduct()
      .|.semiApply()
      .|.|.filter("m:O")
      .|.|.argument("m")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "inserts eager between Create and NodeByLabelScan if label overlap with labels from AntiSemiApply"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "M"))
      .cartesianProduct()
      .|.antiSemiApply()
      .|.|.filter("m:O")
      .|.|.argument("m")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "M"))
      .eager(ListSet(LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(6))))))
      .cartesianProduct()
      .|.antiSemiApply()
      .|.|.filter("m:O")
      .|.|.argument("m")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
      .build())
  }

  test(
    "inserts eager between Create and NodeByLabelScan if label overlap with labels outside of SelectOrSemiApply"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "M"))
      .cartesianProduct()
      .|.selectOrSemiApply("true")
      .|.|.filter("m:O")
      .|.|.argument("m")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNode("o", "M"))
        .eager(ListSet(LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(6))))))
        .cartesianProduct()
        .|.selectOrSemiApply("true")
        .|.|.filter("m:O")
        .|.|.argument("m")
        .|.nodeByLabelScan("m", "M")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts eager between Create and NodeByLabelScan if label overlap with labels for new node in SelectOrSemiApply"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "M"))
      .selectOrSemiApply("n.prop > 0")
      .|.nodeByLabelScan("m", "M", "n")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNode("o", "M"))
        .eager(ListSet(LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(3))))))
        .selectOrSemiApply("n.prop > 0")
        .|.nodeByLabelScan("m", "M", "n")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "does not insert eager between Create and NodeByLabelScan if label overlap with labels from RollUpApply"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "O"))
      .cartesianProduct()
      .|.rollUpApply("m", "ms")
      .|.|.filter("m:O")
      .|.|.argument("m")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "inserts eager between Create and NodeByLabelScan if label overlap with labels outside of RollUpApply"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "M"))
      .cartesianProduct()
      .|.rollUpApply("m", "ms")
      .|.|.filter("m:O")
      .|.|.argument("m")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "M"))
      .eager(ListSet(LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(6))))))
      .cartesianProduct()
      .|.rollUpApply("m", "ms")
      .|.|.filter("m:O")
      .|.|.argument("m")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
      .build())
  }

  test(
    "inserts eager between Create and NodeByLabelScan if label overlap with labels outside of SubqueryForeach"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "M"))
      .cartesianProduct()
      .|.subqueryForeach()
      .|.|.filter("m:O")
      .|.|.argument("m")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "M"))
      .eager(ListSet(LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(6))))))
      .cartesianProduct()
      .|.subqueryForeach()
      .|.|.filter("m:O")
      .|.|.argument("m")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
      .build())
  }

  test(
    "inserts eager between Create and NodeByLabelScan if label overlap with labels in and outside of Apply"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "M", "O"))
      .cartesianProduct()
      .|.apply()
      .|.|.filter("m:O")
      .|.|.argument("m")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNode("o", "M", "O"))
        .eager(ListSet(
          LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(6)))),
          LabelReadSetConflict(labelName("O"), Some(Conflict(Id(1), Id(6))))
        ))
        .cartesianProduct()
        .|.apply()
        .|.|.filter("m:O")
        .|.|.argument("m")
        .|.nodeByLabelScan("m", "M")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "does not insert eager between Create and NodeByLabelScan if label overlap with labels only outside of Apply"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "M"))
      .cartesianProduct()
      .|.apply()
      .|.|.filter("m:O")
      .|.|.argument("m")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "inserts no eager between Create and NodeByLabelScan if no label overlap, but conflicting label in Filter after create"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .filter("m:O")
      .create(createNode("o", "O"))
      .cartesianProduct()
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "inserts no eager between Create and NodeByLabelScan if no label overlap with ANDed labels, same label in NodeByLabelScan"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "M"))
      .cartesianProduct()
      .|.filter("m:O")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "inserts eager between Create and NodeByLabelScan if label overlap, and other label in Filter after create"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .filter("m:O")
      .create(createNode("o", "M"))
      .cartesianProduct()
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .filter("m:O")
        .create(createNode("o", "M"))
        .eager(ListSet(LabelReadSetConflict(labelName("M"), Some(Conflict(Id(2), Id(4))))))
        .cartesianProduct()
        .|.nodeByLabelScan("m", "M")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts eager between Create and later NodeByLabelScan"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .apply()
      .|.nodeByLabelScan("m", "M")
      .create(createNode("o", "M"))
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .apply()
        .|.nodeByLabelScan("m", "M")
        .eager(ListSet(LabelReadSetConflict(labelName("M"), Some(Conflict(Id(3), Id(2))))))
        .create(createNode("o", "M"))
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts no eager between bare Create and IndexScan") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o"))
      .cartesianProduct()
      .|.nodeIndexOperator("m:M(prop)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts no eager between Create and IndexScan if no property overlap") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNodeWithProperties("o", Seq("M"), "{foo: 5}"))
      .cartesianProduct()
      .|.nodeIndexOperator("m:M(prop)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts eager between Create and IndexScan if property overlap") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNodeWithProperties("o", Seq("M"), "{prop: 5}"))
      .cartesianProduct()
      .|.nodeIndexOperator("m:M(prop)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNodeWithProperties("o", Seq("M"), "{prop: 5}"))
        .eager(ListSet(
          PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(1), Id(3)))),
          LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(3))))
        ))
        .cartesianProduct()
        .|.nodeIndexOperator("m:M(prop)")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager between Create and IndexScan if unknown property created") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNodeWithProperties("o", Seq("M"), "$foo"))
      .cartesianProduct()
      .|.nodeIndexOperator("m:M(prop)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNodeWithProperties("o", Seq("M"), "$foo"))
        .eager(ListSet(
          UnknownPropertyReadSetConflict(Some(Conflict(Id(1), Id(3)))),
          LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(3))))
        ))
        .cartesianProduct()
        .|.nodeIndexOperator("m:M(prop)")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager between Create and NodeByIdSeek (single ID) with label filter if read through stable iterator") {
    // This plan does actually not need to be Eager.
    // But since we only eagerize a single row, we accept that the analysis is imperfect here.
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("m", "N"))
      .filter("n:N")
      .nodeByIdSeek("n", Set.empty, 1)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("m", "N"))
      .filter("n:N")
      .eager(ListSet(LabelReadSetConflict(labelName("N"), Some(Conflict(Id(1), Id(3))))))
      .nodeByIdSeek("n", Set.empty, 1)
      .build())
  }

  test(
    "inserts eager between Create and NodeByIdSeek (multiple IDs) with label filter if read through stable iterator"
  ) {
    // This plan looks like we would not need Eagerness, but actually the IDs 2 and 3 do not need to exist yet
    // and the newly created node could get one of these IDs.
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("m", "N"))
      .filter("n:N")
      .nodeByIdSeek("n", Set.empty, 1, 2, 3)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("m", "N"))
      .filter("n:N")
      .eager(ListSet(LabelReadSetConflict(labelName("N"), Some(Conflict(Id(1), Id(3))))))
      .nodeByIdSeek("n", Set.empty, 1, 2, 3)
      .build())
  }

  test(
    "inserts eager between Create and NodeByElementIdSeek (single ID) with label filter if read through stable iterator"
  ) {
    // This plan does actually not need to be Eager.
    // But since we only eagerize a single row, we accept that the analysis is imperfect here.
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("m", "N"))
      .filter("n:N")
      .nodeByElementIdSeek("n", Set.empty, 1)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("m", "N"))
      .filter("n:N")
      .eager(ListSet(LabelReadSetConflict(labelName("N"), Some(Conflict(Id(1), Id(3))))))
      .nodeByElementIdSeek("n", Set.empty, 1)
      .build())
  }

  test(
    "inserts eager between Create and NodeByElementIdSeek (multiple IDs) with label filter if read through stable iterator"
  ) {
    // This plan looks like we would not need Eagerness, but actually the IDs 2 and 3 do not need to exist yet
    // and the newly created node could get one of these IDs.
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("m", "N"))
      .filter("n:N")
      .nodeByElementIdSeek("n", Set.empty, 1, 2, 3)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("m", "N"))
      .filter("n:N")
      .eager(ListSet(LabelReadSetConflict(labelName("N"), Some(Conflict(Id(1), Id(3))))))
      .nodeByElementIdSeek("n", Set.empty, 1, 2, 3)
      .build())
  }

  test(
    "inserts no eager between Create and MATCH with a different label but same property, when other operand is a variable"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("m")
      .filter("m.prop = x")
      .apply()
      .|.nodeByLabelScan("m", "M")
      .create(createNodeWithProperties("n", Seq("N"), "{prop: 5}"))
      .unwind("[1,2] AS x")
      .argument()
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts eager between create and label read in nested plan expression") {
    val nestedPlan = subPlanBuilderWithIdOffset()
      .filter("m:N")
      .expand("(n)-[r]->(m)")
      .argument("n")
      .build()

    val nestedPlanExpression = NestedPlanExistsExpression(
      nestedPlan,
      s"EXISTS { MATCH (n)-[r]->(m:N) }"
    )(pos)

    val planBuilder = new LogicalPlanBuilder()
      .produceResults("x")
      .projection("n.foo AS x")
      .filterExpression(nestedPlanExpression)
      .create(createNode("n", "N"))
      .unwind("[1,2] AS x")
      .argument()
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("x")
        .projection("n.foo AS x")
        .filterExpression(nestedPlanExpression)
        .eager(ListSet(LabelReadSetConflict(labelName("N"), Some(Conflict(Id(3), Id(2))))))
        .create(createNode("n", "N"))
        .unwind("[1,2] AS x")
        .argument()
        .build()
    )
  }

  // Read vs Merge conflicts

  test("inserts no eager between Merge and AllNodeScan if read through stable iterator") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("n")
      .merge(nodes = Seq(createNode("n")))
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts no eager between Merge and and its child plans") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("n")
      .merge(nodes = Seq(createNode("n", "N")))
      .filter("n", "N")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts eager between Merge and AllNodeScan if read through unstable iterator") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .apply()
      .|.merge(nodes = Seq(createNode("o")))
      .|.allNodeScan("o")
      .cartesianProduct()
      .|.allNodeScan("m")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .apply()
        .|.merge(nodes = Seq(createNode("o")))
        .|.allNodeScan("o")
        .eager(ListSet(ReadCreateConflict(Some(Conflict(Id(2), Id(5))))))
        .cartesianProduct()
        .|.allNodeScan("m")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts no eager between Merge with ON MATCH and IndexScan if no property overlap") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .merge(nodes = Seq(createNode("m")), onMatch = Seq(setNodeProperty("m", "foo", "42")))
      .cartesianProduct()
      .|.nodeIndexOperator("m:M(prop)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("inserts eager between Merge with ON MATCH and IndexScan if property overlap") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .apply()
      .|.merge(nodes = Seq(createNode("o")), onMatch = Seq(setNodeProperty("o", "prop", "42")))
      .|.allNodeScan("o")
      .cartesianProduct()
      .|.nodeIndexOperator("m:M(prop)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .apply()
        .|.merge(nodes = Seq(createNode("o")), onMatch = Seq(setNodeProperty("o", "prop", "42")))
        .|.allNodeScan("o")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(3), Id(6))))))
        .cartesianProduct()
        .|.nodeIndexOperator("m:M(prop)")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager between Merge with ON MATCH and IndexScan if property overlap (setting multiple properties)") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .apply()
      .|.merge(nodes = Seq(createNode("o")), onMatch = Seq(setNodeProperties("o", ("prop", "42"), ("foo", "42"))))
      .|.allNodeScan("o")
      .cartesianProduct()
      .|.nodeIndexOperator("m:M(prop)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .apply()
        .|.merge(nodes = Seq(createNode("o")), onMatch = Seq(setNodeProperties("o", ("prop", "42"), ("foo", "42"))))
        .|.allNodeScan("o")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(3), Id(6))))))
        .cartesianProduct()
        .|.nodeIndexOperator("m:M(prop)")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager between Merge with ON MATCH and IndexScan if property overlap (setting properties from map)") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .apply()
      .|.merge(
        nodes = Seq(createNode("o")),
        onMatch = Seq(setNodePropertiesFromMap("o", "{prop: 42}", removeOtherProps = false))
      )
      .|.allNodeScan("o")
      .cartesianProduct()
      .|.nodeIndexOperator("m:M(prop)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .apply()
        .|.merge(
          nodes = Seq(createNode("o")),
          onMatch = Seq(setNodePropertiesFromMap("o", "{prop: 42}", removeOtherProps = false))
        )
        .|.allNodeScan("o")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(3), Id(6))))))
        .cartesianProduct()
        .|.nodeIndexOperator("m:M(prop)")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts eager between Merge with ON MATCH and IndexScan if all property removed (setting properties from map)"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .apply()
      .|.merge(
        nodes = Seq(createNode("o")),
        onMatch = Seq(setNodePropertiesFromMap("o", "{foo: 42}", removeOtherProps = true))
      )
      .|.allNodeScan("o")
      .cartesianProduct()
      .|.nodeIndexOperator("m:M(prop)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .apply()
        .|.merge(
          nodes = Seq(createNode("o")),
          onMatch = Seq(setNodePropertiesFromMap("o", "{foo: 42}", removeOtherProps = true))
        )
        .|.allNodeScan("o")
        .eager(ListSet(UnknownPropertyReadSetConflict(Some(Conflict(Id(3), Id(6))))))
        .cartesianProduct()
        .|.nodeIndexOperator("m:M(prop)")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager between Merge with ON CREATE and IndexScan if property overlap") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .apply()
      .|.merge(nodes = Seq(createNode("o", "M")), onCreate = Seq(setNodeProperty("o", "prop", "42")))
      .|.nodeByLabelScan("o", "M")
      .cartesianProduct()
      .|.nodeIndexOperator("m:M(prop)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .apply()
        .|.merge(nodes = Seq(createNode("o", "M")), onCreate = Seq(setNodeProperty("o", "prop", "42")))
        .|.nodeByLabelScan("o", "M")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(3), Id(6))))))
        .cartesianProduct()
        .|.nodeIndexOperator("m:M(prop)")
        .allNodeScan("n")
        .build()
    )
  }

  // Insert Eager at best position

  test("inserts eager between conflicting plans at the cardinality minimum between the two plans") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo").withCardinality(50)
      .projection("n.prop AS foo").withCardinality(50)
      .expand("(n)-->(m)").withCardinality(50)
      .filter("5 > 3").withCardinality(10) // Minimum of prop conflict
      .setNodeProperty("n", "prop", "5").withCardinality(100)
      .allNodeScan("n").withCardinality(100)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .projection("n.prop AS foo")
        .expand("(n)-->(m)")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(4), Id(1))))))
        .filter("5 > 3")
        .setNodeProperty("n", "prop", "5")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts eager between conflicting plans at the cardinality minima of two separate conflicts (non-intersecting candidate lists)"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo").withCardinality(20)
      .projection("n.foo AS foo").withCardinality(20)
      .expand("(n)-->(o)").withCardinality(20) // Minimum of foo conflict
      .filter("5 > 3").withCardinality(40)
      .setNodeProperty("n", "foo", "5").withCardinality(50)
      .projection("n.prop AS prop").withCardinality(50)
      .expand("(n)-->(m)").withCardinality(50)
      .filter("5 > 3").withCardinality(10) // Minimum of prop conflict
      .setNodeProperty("n", "prop", "5").withCardinality(100)
      .allNodeScan("n").withCardinality(100)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .projection("n.foo AS foo")
        .eager(ListSet(PropertyReadSetConflict(propName("foo"), Some(Conflict(Id(4), Id(1))))))
        .expand("(n)-->(o)")
        .filter("5 > 3")
        .setNodeProperty("n", "foo", "5")
        .projection("n.prop AS prop")
        .expand("(n)-->(m)")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(8), Id(5))))))
        .filter("5 > 3")
        .setNodeProperty("n", "prop", "5")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts eager between conflicting plans at the cardinality minima of two separate conflicts (one candidate list subset of the other, same minimum)"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo").withCardinality(25)
      .projection("n.prop AS prop").withCardinality(25)
      .projection("n.foo AS foo").withCardinality(25)
      .expand("(n)-->(o)").withCardinality(20) // Minimum of both conflicts
      .expand("(n)-->(m)").withCardinality(50)
      .setNodeProperty("n", "foo", "5").withCardinality(75)
      .filter("5 > 3").withCardinality(75)
      .setNodeProperty("n", "prop", "5").withCardinality(100)
      .allNodeScan("n").withCardinality(100)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .projection("n.prop AS prop")
        .projection("n.foo AS foo")
        .eager(ListSet(
          PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(7), Id(1)))),
          PropertyReadSetConflict(propName("foo"), Some(Conflict(Id(5), Id(2))))
        ))
        .expand("(n)-->(o)")
        .expand("(n)-->(m)")
        .setNodeProperty("n", "foo", "5")
        .filter("5 > 3")
        .setNodeProperty("n", "prop", "5")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts eager between conflicting plans at the cardinality minima of two separate conflicts (one candidate list subset of the other, different minima)"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo").withCardinality(25)
      .projection("n.prop AS prop").withCardinality(25)
      .projection("n.foo AS foo").withCardinality(25)
      .expand("(n)-->(o)").withCardinality(20) // Minimum of foo conflict
      .expand("(n)-->(m)").withCardinality(50)
      .setNodeProperty("n", "foo", "5").withCardinality(75)
      .filter("5 > 3").withCardinality(10) // Mininum of prop conflict
      .setNodeProperty("n", "prop", "5").withCardinality(100)
      .allNodeScan("n").withCardinality(100)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .projection("n.prop AS prop")
        .projection("n.foo AS foo")
        .eager(ListSet(
          PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(7), Id(1)))),
          PropertyReadSetConflict(propName("foo"), Some(Conflict(Id(5), Id(2))))
        ))
        .expand("(n)-->(o)")
        .expand("(n)-->(m)")
        .setNodeProperty("n", "foo", "5")
        .filter("5 > 3")
        .setNodeProperty("n", "prop", "5")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts eager between conflicting plans at the cardinality minima of two separate conflicts (overlapping candidate lists, same minimum)"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo").withCardinality(25)
      .projection("n.foo AS foo").withCardinality(100)
      .expand("(n)-->(p)").withCardinality(50)
      .projection("n.prop AS prop").withCardinality(50)
      .expand("(n)-->(o)").withCardinality(10) // Minimum of both conflicts
      .setNodeProperty("n", "foo", "5").withCardinality(50)
      .expand("(n)-->(m)").withCardinality(50)
      .setNodeProperty("n", "prop", "5").withCardinality(100)
      .allNodeScan("n").withCardinality(100)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .projection("n.foo AS foo")
        .expand("(n)-->(p)")
        .projection("n.prop AS prop")
        .eager(ListSet(
          PropertyReadSetConflict(propName("foo"), Some(Conflict(Id(5), Id(1)))),
          PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(7), Id(3))))
        ))
        .expand("(n)-->(o)")
        .setNodeProperty("n", "foo", "5")
        .expand("(n)-->(m)")
        .setNodeProperty("n", "prop", "5")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts eager between conflicting plans at the cardinality minima of two separate conflicts (overlapping candidate lists, first minimum in intersection)"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo").withCardinality(25)
      .projection("n.foo AS foo").withCardinality(100)
      .expand("(n)-->(p)").withCardinality(5) // Minimum of foo conflict
      .projection("n.prop AS prop").withCardinality(50)
      .expand("(n)-->(o)").withCardinality(10) // Minimum of prop conflict (in intersection)
      .setNodeProperty("n", "foo", "5").withCardinality(50)
      .expand("(n)-->(m)").withCardinality(50)
      .setNodeProperty("n", "prop", "5").withCardinality(100)
      .allNodeScan("n").withCardinality(100)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .projection("n.foo AS foo")
        .expand("(n)-->(p)")
        .projection("n.prop AS prop")
        .eager(ListSet(
          PropertyReadSetConflict(propName("foo"), Some(Conflict(Id(5), Id(1)))),
          PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(7), Id(3))))
        ))
        .expand("(n)-->(o)")
        .setNodeProperty("n", "foo", "5")
        .expand("(n)-->(m)")
        .setNodeProperty("n", "prop", "5")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts eager between conflicting plans at the cardinality minima of two separate conflicts (overlapping candidate lists, second minimum in intersection)"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo").withCardinality(25)
      .projection("n.foo AS foo").withCardinality(100)
      .expand("(n)-->(p)").withCardinality(15)
      .projection("n.prop AS prop").withCardinality(50)
      .expand("(n)-->(o)").withCardinality(10) // Minimum of foo conflict (in intersection)
      .setNodeProperty("n", "foo", "5").withCardinality(50)
      .expand("(n)-->(m)").withCardinality(5) // Minimum of prop conflict
      .setNodeProperty("n", "prop", "5").withCardinality(100)
      .allNodeScan("n").withCardinality(100)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .projection("n.foo AS foo")
        .expand("(n)-->(p)")
        .projection("n.prop AS prop")
        .eager(ListSet(
          PropertyReadSetConflict(propName("foo"), Some(Conflict(Id(5), Id(1)))),
          PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(7), Id(3))))
        ))
        .expand("(n)-->(o)")
        .setNodeProperty("n", "foo", "5")
        .expand("(n)-->(m)")
        .setNodeProperty("n", "prop", "5")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts eager between conflicting plans at the cardinality minima of two separate conflicts (overlapping candidate lists, different minima, none in intersection)"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo").withCardinality(100)
      .projection("n.foo AS foo").withCardinality(100)
      .expand("(n)-->(p)").withCardinality(5) // Minimum  of foo conflict
      .projection("n.prop AS prop").withCardinality(100)
      .expand("(n)-->(o)").withCardinality(8) // Minimum of intersection
      .setNodeProperty("n", "foo", "5").withCardinality(100)
      .expand("(n)-->(m)").withCardinality(5) // Minimum  of prop conflict
      .setNodeProperty("n", "prop", "5").withCardinality(100)
      .allNodeScan("n").withCardinality(100)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .projection("n.foo AS foo")
        .eager(ListSet(PropertyReadSetConflict(propName("foo"), Some(Conflict(Id(5), Id(1))))))
        .expand("(n)-->(p)")
        .projection("n.prop AS prop")
        .expand("(n)-->(o)")
        .setNodeProperty("n", "foo", "5")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(7), Id(3))))))
        .expand("(n)-->(m)")
        .setNodeProperty("n", "prop", "5")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager between label create and label read (Filter) directly after AllNodeScan if cheapest") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("m")
      .create(createNode("m", "N", "O"))
      .filter("n:N").withCardinality(800)
      .filter("n:O").withCardinality(900)
      .unwind("n.prop AS prop").withCardinality(1000)
      .apply().withCardinality(10)
      .|.allNodeScan("n").withCardinality(10)
      .argument().withCardinality(1)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("m")
        .create(createNode("m", "N", "O"))
        .filter("n:N")
        .filter("n:O")
        .unwind("n.prop AS prop")
        .eager(ListSet(
          LabelReadSetConflict(labelName("N"), Some(Conflict(Id(1), Id(6)))),
          LabelReadSetConflict(labelName("O"), Some(Conflict(Id(1), Id(6))))
        ))
        .apply()
        .|.allNodeScan("n")
        .argument()
        .build()
    )
  }

  // Apply-Plans

  test(
    "inserts eager between property read on the LHS and property write on top of an Apply (cardinality lower after Apply)"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .setNodeProperty("n", "prop", "5")
      .apply().withCardinality(5)
      .|.argument("n")
      .projection("n.prop AS foo").withCardinality(10)
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .setNodeProperty("n", "prop", "5")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(1), Id(4))))))
        .apply()
        .|.argument("n")
        .projection("n.prop AS foo")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager between property read on the LHS and property write on the RHS of an Apply") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .apply()
      .|.setNodeProperty("n", "prop", "5")
      .|.argument("n")
      .projection("n.prop AS foo")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .apply()
        .|.setNodeProperty("n", "prop", "5")
        .|.argument("n")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(2), Id(4))))))
        .projection("n.prop AS foo")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager between property read on the RHS and property write on top of an Apply") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .setNodeProperty("n", "prop", "5")
      .apply()
      .|.projection("n.prop AS foo")
      .|.argument("n")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .setNodeProperty("n", "prop", "5")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(1), Id(3))))))
        .apply()
        .|.projection("n.prop AS foo")
        .|.argument("n")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager between property read on the RHS and property write on the RHS of an Apply") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .apply()
      .|.setNodeProperty("n", "prop", "5")
      .|.projection("n.prop AS foo")
      .|.argument("n")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .apply()
        .|.setNodeProperty("n", "prop", "5")
        .|.eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(2), Id(3))))))
        .|.projection("n.prop AS foo")
        .|.argument("n")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager between property read on the RHS of an Apply and property write on the RHS of another Apply") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .apply()
      .|.setNodeProperty("n", "prop", "5")
      .|.argument("n")
      .apply()
      .|.projection("n.prop AS foo")
      .|.argument("n")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .apply()
        .|.setNodeProperty("n", "prop", "5")
        .|.argument("n")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(2), Id(5))))))
        .apply()
        .|.projection("n.prop AS foo")
        .|.argument("n")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager for transactional apply for otherwise stable iterators") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults()
      .emptyResult()
      .transactionApply()
      .|.setLabels("m", "A")
      .|.expand("(n)--(m)")
      .|.argument("n")
      .nodeByLabelScan("n", "A")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults()
        .emptyResult()
        .transactionApply()
        .|.setLabels("m", "A")
        .|.expand("(n)--(m)")
        .|.argument("n")
        .eager(ListSet(LabelReadSetConflict(labelName("A"), Some(Conflict(Id(3), Id(6))))))
        .nodeByLabelScan("n", "A")
        .build()
    )
  }

  test("inserts no eager for DETACH DELETE in transactions") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("one")
      .transactionApply()
      .|.projection("1 as one")
      .|.detachDeleteNode("n")
      .|.argument("n")
      .nodeByLabelScan("n", "A")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("insert eager when apply plan is conflicting with the outside") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults()
      .setLabels("n", "A")
      .selectOrSemiApply("a:A")
      .|.expand("(a)-[r]->(n)")
      .|.argument("a")
      .allNodeScan("a")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults()
        .setLabels("n", "A")
        .eager(ListSet(LabelReadSetConflict(labelName("A"), Some(Conflict(Id(1), Id(2))))))
        .selectOrSemiApply("a:A")
        .|.expand("(a)-[r]->(n)")
        .|.argument("a")
        .allNodeScan("a")
        .build()
    )
  }

  test("insert eager when apply plan is conflicting with the LHS") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults()
      .selectOrSemiApply("a:A")
      .|.expand("(a)-[r]->(n)")
      .|.argument("a")
      .setLabels("a", "A")
      .allNodeScan("a")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults()
        .selectOrSemiApply("a:A")
        .|.expand("(a)-[r]->(n)")
        .|.argument("a")
        .eager(ListSet(LabelReadSetConflict(labelName("A"), Some(Conflict(Id(4), Id(1))))))
        .setLabels("a", "A")
        .allNodeScan("a")
        .build()
    )
  }

  test("does not support when apply plan is conflicting with the RHS") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults()
      .selectOrSemiApply("a:A")
      .|.setLabels("n", "A")
      .|.expand("(a)-[r]->(n)")
      .|.argument("a")
      .allNodeScan("a")
    val plan = planBuilder.build()

    // Currently the RHS of any SemiApply variant must be read-only
    an[IllegalStateException] should be thrownBy {
      EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
        plan,
        planBuilder.getSemanticTable
      )
    }
  }

  // Non-apply binary plans

  test("inserts eager in LHS in a conflict between LHS and Top of a CartesianProduct") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("`count(*)`")
      .aggregation(Seq(), Seq("count(*) AS `count(*)`"))
      .setLabels("a", "B")
      .cartesianProduct().withCardinality(2)
      .|.allNodeScan("b")
      .filter("a:B").withCardinality(1)
      .nodeByLabelScan("a", "A")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("`count(*)`")
        .aggregation(Seq(), Seq("count(*) AS `count(*)`"))
        .setLabels("a", "B")
        .cartesianProduct()
        .|.allNodeScan("b")
        .eager(ListSet(LabelReadSetConflict(labelName("B"), Some(Conflict(Id(2), Id(5))))))
        .filter("a:B")
        .nodeByLabelScan("a", "A")
        .build()
    )
  }

  test("inserts eager on Top in a conflict between LHS and Top of a CartesianProduct") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("`count(*)`")
      .aggregation(Seq(), Seq("count(*) AS `count(*)`"))
      .setLabels("a", "B")
      .cartesianProduct().withCardinality(1)
      .|.allNodeScan("b")
      .filter("a:B").withCardinality(2)
      .nodeByLabelScan("a", "A")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("`count(*)`")
        .aggregation(Seq(), Seq("count(*) AS `count(*)`"))
        .setLabels("a", "B")
        .eager(ListSet(LabelReadSetConflict(labelName("B"), Some(Conflict(Id(2), Id(5))))))
        .cartesianProduct()
        .|.allNodeScan("b")
        .filter("a:B")
        .nodeByLabelScan("a", "A")
        .build()
    )
  }

  test("inserts eager on top in a conflict between RHS and Top of a CartesianProduct") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .projection("n.prop AS foo")
      .cartesianProduct().withCardinality(10)
      .|.setNodeProperty("n", "prop", "5").withCardinality(1) // Eager must be on Top anyway
      .|.allNodeScan("n")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .projection("n.prop AS foo")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(3), Id(1))))))
        .cartesianProduct()
        .|.setNodeProperty("n", "prop", "5")
        .|.allNodeScan("n")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager in a conflict between LHS and RHS of a CartesianProduct") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .cartesianProduct()
      .|.projection("m.prop AS foo")
      .|.allNodeScan("m")
      .setNodeProperty("n", "prop", "5")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .cartesianProduct()
        .|.projection("m.prop AS foo")
        .|.allNodeScan("m")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(4), Id(2))))))
        .setNodeProperty("n", "prop", "5")
        .allNodeScan("n")
        .build()
    )
  }

  test("eagerize nested cartesian products") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .setLabels("n", "Two")
      .cartesianProduct()
      .|.cartesianProduct()
      .|.|.nodeByLabelScan("m2", "Two")
      .|.nodeByLabelScan("m1", "Two")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .setLabels("n", "Two")
        .eager(ListSet(
          LabelReadSetConflict(labelName("Two"), Some(Conflict(Id(2), Id(5)))),
          LabelReadSetConflict(labelName("Two"), Some(Conflict(Id(2), Id(6))))
        ))
        .cartesianProduct()
        .|.cartesianProduct()
        .|.|.nodeByLabelScan("m2", "Two")
        .|.nodeByLabelScan("m1", "Two")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager in RHS in a conflict between RHS and Top of a join") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .projection("n.prop AS foo")
      .nodeHashJoin("n").withCardinality(2)
      .|.setNodeProperty("n", "prop", "5").withCardinality(1)
      .|.allNodeScan("n")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .projection("n.prop AS foo")
        .nodeHashJoin("n")
        .|.eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(3), Id(1))))))
        .|.setNodeProperty("n", "prop", "5")
        .|.allNodeScan("n")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts eager on top in a conflict between RHS and Top of a join") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .projection("n.prop AS foo")
      .nodeHashJoin("n").withCardinality(1)
      .|.setNodeProperty("n", "prop", "5").withCardinality(2)
      .|.allNodeScan("n")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .projection("n.prop AS foo")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(3), Id(1))))))
        .nodeHashJoin("n")
        .|.setNodeProperty("n", "prop", "5")
        .|.allNodeScan("n")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts Eager if there is a conflict between LHS and Top of an AssertSameNode.") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .setNodeProperty("n", "prop", "5")
      .assertSameNode("n")
      .|.allNodeScan("n")
      .projection("n.prop AS foo")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .setNodeProperty("n", "prop", "5")
        .assertSameNode("n")
        .|.allNodeScan("n")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(1), Id(4))))))
        .projection("n.prop AS foo")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts Eager on Top if there is a conflict between RHS and Top of an AssertSameNode.") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .setNodeProperty("n", "prop", "5")
      .assertSameNode("n").withCardinality(10)
      .|.projection("n.prop AS foo").withCardinality(1)
      .|.allNodeScan("n")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .setNodeProperty("n", "prop", "5")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(1), Id(3))))))
        .assertSameNode("n")
        .|.projection("n.prop AS foo")
        .|.allNodeScan("n")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts Eager if there is a conflict between LHS and Top of a Union.") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .setNodeProperty("n", "prop", "5")
      .union()
      .|.allNodeScan("n")
      .projection("n.prop AS foo")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .setNodeProperty("n", "prop", "5")
        .union()
        .|.allNodeScan("n")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(1), Id(4))))))
        .projection("n.prop AS foo")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts Eager if there is a conflict between RHS and Top of a Union.") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .setNodeProperty("n", "prop", "5")
      .union()
      .|.projection("n.prop AS foo")
      .|.allNodeScan("n")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .setNodeProperty("n", "prop", "5")
        .union()
        .|.eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(1), Id(3))))))
        .|.projection("n.prop AS foo")
        .|.allNodeScan("n")
        .allNodeScan("n")
        .build()
    )
  }

  test("inserts Eager if there are two conflict in a Union plan: LHS vs Top and RHS vs Top.") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .setNodeProperty("n", "prop", "5")
      .union().withCardinality(10)
      .|.projection("n.prop AS foo2").withCardinality(5)
      .|.allNodeScan("n").withCardinality(5)
      .projection("n.prop AS foo").withCardinality(5)
      .allNodeScan("n").withCardinality(5)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .setNodeProperty("n", "prop", "5")
        .union()
        .|.eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(1), Id(3))))))
        .|.projection("n.prop AS foo2")
        .|.allNodeScan("n")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(1), Id(5))))))
        .projection("n.prop AS foo")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "should not insert Eager if two different created nodes in the same operator have together the labels from a NodeByLabelScan"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("d")
      .apply()
      .|.filter("c:B")
      .|.nodeByLabelScan("c", "A")
      .create(createNode("a", "A"), createNode("b", "B"))
      .argument()

    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "should insert Eager if two different created nodes in the same operator overlap with a FilterExpression"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("d")
      .apply()
      .|.filter("c:!B")
      .|.nodeByLabelScan("c", "A")
      .create(createNode("a", "A"), createNode("b", "B"))
      .argument()

    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(new LogicalPlanBuilder()
      .produceResults("d")
      .apply()
      .|.filter("c:!B")
      .|.nodeByLabelScan("c", "A")
      .eager(ListSet(LabelReadSetConflict(labelName("A"), Some(Conflict(Id(4), Id(3))))))
      .create(createNode("a", "A"), createNode("b", "B"))
      .argument()
      .build())
  }

  test(
    "inserts Eager if there are two conflict in a Union plan: LHS vs Top and RHS vs Top (LHS and RHS are identical plans)."
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .setNodeProperty("n", "prop", "5")
      .union().withCardinality(10)
      .|.projection("n.prop AS foo").withCardinality(5)
      .|.allNodeScan("n").withCardinality(5)
      .projection("n.prop AS foo").withCardinality(5)
      .allNodeScan("n").withCardinality(5)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .setNodeProperty("n", "prop", "5")
        .union()
        .|.eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(1), Id(3))))))
        .|.projection("n.prop AS foo")
        .|.allNodeScan("n")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(1), Id(5))))))
        .projection("n.prop AS foo")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts Eager if there is a conflict in a Union plan: RHS vs Top (LHS and RHS are identical plans with a filter)."
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("foo")
      .create(createNodeWithProperties("m", Seq.empty, "{prop: 5}"))
      .union().withCardinality(10)
      .|.filter("n.prop > 0").withCardinality(5)
      .|.allNodeScan("n").withCardinality(5)
      .filter("n.prop > 0").withCardinality(5)
      .allNodeScan("n").withCardinality(5)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )

    result should equal(
      new LogicalPlanBuilder()
        .produceResults("foo")
        .create(createNodeWithProperties("m", Seq(), "{prop: 5}"))
        .union()
        .|.filter("n.prop > 0")
        .|.eager(ListSet(ReadCreateConflict(Some(Conflict(Id(1), Id(4))))))
        .|.allNodeScan("n")
        .filter("n.prop > 0")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "Should only reference the conflicting label when there is an eagerness conflict between a write within a forEach and a read after"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .apply()
      .|.nodeByLabelScan("d", "Event")
      .foreach(
        "x",
        "[1]",
        Seq(
          createPattern(
            Seq(createNode("e", "Event"), createNode("p", "Place")),
            Seq(createRelationship("i", "e", "IN", "p"))
          ),
          setNodeProperty("e", "foo", "'e_bar'")
        )
      )
      .argument()

    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .apply()
        .|.nodeByLabelScan("d", "Event")
        .eager(ListSet(LabelReadSetConflict(labelName("Event"), Some(Conflict(Id(4), Id(3))))))
        .foreach(
          "x",
          "[1]",
          Seq(
            createPattern(
              Seq(createNode("e", "Event"), createNode("p", "Place")),
              Seq(createRelationship("i", "e", "IN", "p"))
            ),
            setNodeProperty("e", "foo", "'e_bar'")
          )
        )
        .argument()
        .build()
    )
  }

  test(
    "Should insert an eager when there is a conflict between a write within a forEach and a read after"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("d")
      .apply()
      .|.nodeByLabelScan("d", "Event")
      .foreach("x", "[1]", Seq(createPattern(Seq(createNode("e", "Event")))))
      .argument()

    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("d")
        .apply()
        .|.nodeByLabelScan("d", "Event")
        .eager(ListSet(LabelReadSetConflict(labelName("Event"), Some(Conflict(Id(3), Id(2))))))
        .foreach("x", "[1]", Seq(createPattern(Seq(createNode("e", "Event")))))
        .argument()
        .build()
    )
  }

  test(
    "Should insert an eager when there is a conflict between a Remove Label within a forEach and a read after"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("d")
      .apply()
      .|.nodeByLabelScan("d", "Event")
      .foreach("x", "[1]", Seq(RemoveLabelPattern("e", Seq(labelName("Event")))))
      .argument()

    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("d")
        .apply()
        .|.nodeByLabelScan("d", "Event")
        .eager(ListSet(
          LabelReadRemoveConflict(labelName("Event"), Some(Conflict(Id(3), Id(0)))),
          LabelReadRemoveConflict(labelName("Event"), Some(Conflict(Id(3), Id(2))))
        ))
        .foreach("x", "[1]", Seq(RemoveLabelPattern("e", Seq(labelName("Event")))))
        .argument()
        .build()
    )
  }

  test(
    "Should insert an eager when there is a conflict between a write within a forEachApply and a read after"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("d")
      .apply()
      .|.nodeIndexOperator("d:D(foo>0)")
      .foreachApply("num", "[1]")
      .|.create(createNodeWithProperties("n", Seq("D"), "{foo: num}"))
      .|.argument("num")
      .argument()

    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("d")
        .apply()
        .|.nodeIndexOperator("d:D(foo>0)")
        .eager(ListSet(
          PropertyReadSetConflict(propName("foo"), Some(Conflict(Id(4), Id(2)))),
          LabelReadSetConflict(labelName("D"), Some(Conflict(Id(4), Id(2))))
        ))
        .foreachApply("num", "[1]")
        .|.create(createNodeWithProperties("n", Seq("D"), "{foo: num}"))
        .|.argument("num")
        .argument()
        .build()
    )
  }

  test(
    "Should not insert an eager when there is no conflict between a write within a forEachApply and a read after"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("d")
      .apply()
      .|.nodeIndexOperator("d:D(bar>0)")
      .foreachApply("num", "[1]")
      .|.create(createNodeWithProperties("n", Seq(), "{foo: num}"))
      .|.argument("num")
      .argument()

    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test(
    "inserts eager between property set and property read (NodeIndexSeekByRange) if property read through unstable iterator"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .setNodeProperty("m", "prop", "5")
      .apply()
      .|.nodeIndexOperator("n:N(prop>5)")
      .allNodeScan("m")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .setNodeProperty("m", "prop", "5")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(2), Id(4))))))
        .apply()
        .|.nodeIndexOperator("n:N(prop > 5)")
        .allNodeScan("m")
        .build()
    )
  }

  test(
    "inserts eager between property set and property read (NodeIndexSeek) if property read through unstable iterator"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .setNodeProperty("m", "prop", "5")
      .apply()
      .|.nodeIndexOperator("n:N(prop=5)")
      .allNodeScan("m")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .setNodeProperty("m", "prop", "5")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(2), Id(4))))))
        .apply()
        .|.nodeIndexOperator("n:N(prop = 5)")
        .allNodeScan("m")
        .build()
    )
  }

  test("inserts eager between Create and NodeIndexSeek if property overlap") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNodeWithProperties("o", Seq("M"), "{prop: 5}"))
      .cartesianProduct()
      .|.nodeIndexOperator("m:M(prop>0)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNodeWithProperties("o", Seq("M"), "{prop: 5}"))
        .eager(ListSet(
          PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(1), Id(3)))),
          LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(3))))
        ))
        .cartesianProduct()
        .|.nodeIndexOperator("m:M(prop>0)")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts eager between property set and property read (NodeUniqueIndexSeek) if property read through unstable iterator"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .setNodeProperty("m", "prop", "5")
      .apply()
      .|.nodeIndexOperator("n:N(prop=5)", unique = true)
      .allNodeScan("m")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .setNodeProperty("m", "prop", "5")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(2), Id(4))))))
        .apply()
        .|.nodeIndexOperator("n:N(prop = 5)", unique = true)
        .allNodeScan("m")
        .build()
    )
  }

  test("inserts eager between Create and NodeUniqueIndexSeek if property overlap") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNodeWithProperties("o", Seq("M"), "{prop: 5}"))
      .cartesianProduct()
      .|.nodeIndexOperator("m:M(prop>0)", unique = true)
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNodeWithProperties("o", Seq("M"), "{prop: 5}"))
        .eager(ListSet(
          PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(1), Id(3)))),
          LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(3))))
        ))
        .cartesianProduct()
        .|.nodeIndexOperator("m:M(prop>0)", unique = true)
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts eager between property set and property read (NodeIndexContainsScan) if property read through unstable iterator"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .setNodeProperty("m", "prop", "5")
      .apply()
      .|.nodeIndexOperator("n:N(prop CONTAINS '1')", indexType = IndexType.TEXT)
      .allNodeScan("m")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .setNodeProperty("m", "prop", "5")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(2), Id(4))))))
        .apply()
        .|.nodeIndexOperator("n:N(prop CONTAINS '1')", indexType = IndexType.TEXT)
        .allNodeScan("m")
        .build()
    )
  }

  test("inserts eager between Create and NodeIndexContainsScan if property overlap") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNodeWithProperties("o", Seq("M"), "{prop: '1'}"))
      .cartesianProduct()
      .|.nodeIndexOperator("m:M(prop CONTAINS '1')")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNodeWithProperties("o", Seq("M"), "{prop: '1'}"))
        .eager(ListSet(
          PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(1), Id(3)))),
          LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(3))))
        ))
        .cartesianProduct()
        .|.nodeIndexOperator("m:M(prop CONTAINS '1')")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "inserts eager between property set and property read (NodeIndexEndsWithScan) if property read through unstable iterator"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .setNodeProperty("m", "prop", "5")
      .apply()
      .|.nodeIndexOperator("n:N(prop ENDS WITH '1')", indexType = IndexType.TEXT)
      .allNodeScan("m")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .setNodeProperty("m", "prop", "5")
        .eager(ListSet(PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(2), Id(4))))))
        .apply()
        .|.nodeIndexOperator("n:N(prop ENDS WITH '1')", indexType = IndexType.TEXT)
        .allNodeScan("m")
        .build()
    )
  }

  test("inserts eager between Create and NodeIndexEndsWithScan if property overlap") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNodeWithProperties("o", Seq("M"), "{prop: '1'}"))
      .cartesianProduct()
      .|.nodeIndexOperator("m:M(prop ENDS WITH '1')")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("o")
        .create(createNodeWithProperties("o", Seq("M"), "{prop: '1'}"))
        .eager(ListSet(
          PropertyReadSetConflict(propName("prop"), Some(Conflict(Id(1), Id(3)))),
          LabelReadSetConflict(labelName("M"), Some(Conflict(Id(1), Id(3))))
        ))
        .cartesianProduct()
        .|.nodeIndexOperator("m:M(prop ENDS WITH '1')")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "Conflicts in when traversing the right hand side of a plan should be found and eagerized."
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("result")
      .projection("1 AS result")
      .setLabels("p", "B")
      .apply()
      .|.cartesianProduct()
      .|.|.nodeByLabelScan("p", "C")
      .|.nodeByLabelScan("o", "B")
      .create(createNode("m", "C"))
      .nodeByLabelScan("n", "A")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("result")
        .projection("1 AS result")
        .setLabels("p", "B")
        .eager(ListSet(LabelReadSetConflict(labelName("B"), Some(EagernessReason.Conflict(Id(2), Id(6))))))
        .apply()
        .|.cartesianProduct()
        .|.|.nodeByLabelScan("p", "C")
        .|.nodeByLabelScan("o", "B")
        .eager(ListSet(LabelReadSetConflict(labelName("C"), Some(EagernessReason.Conflict(Id(7), Id(5))))))
        .create(createNode("m", "C"))
        .nodeByLabelScan("n", "A")
        .build()
    )
  }

  test(
    "should be eager between conflicts found inside cartesian product"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("m")
      .apply()
      .|.cartesianProduct()
      .|.|.nodeByLabelScan("m", "Label")
      .|.nodeByLabelScan("n", "Label")
      .create(createNode("l", "Label"))
      .unwind("[1, 2] AS y")
      .argument()
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("m")
        .apply()
        .|.cartesianProduct()
        .|.|.nodeByLabelScan("m", "Label")
        .|.nodeByLabelScan("n", "Label")
        .eager(ListSet(
          LabelReadSetConflict(labelName("Label"), Some(EagernessReason.Conflict(Id(5), Id(3)))),
          LabelReadSetConflict(labelName("Label"), Some(EagernessReason.Conflict(Id(5), Id(4))))
        ))
        .create(createNode("l", "Label"))
        .unwind("[1, 2] AS y")
        .argument()
        .build()
    )
  }

  // DELETE Tests

  test("Should not be eager between two deletes") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .deleteNode("n")
      .deleteNode("m")
      .cartesianProduct()
      .|.nodeByLabelScan("m", "M")
      .nodeByLabelScan("n", "N")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .deleteNode("n")
        .deleteNode("m")
        .eager(ListSet(ReadDeleteConflict("m", Some(Conflict(Id(3), Id(5))))))
        .cartesianProduct()
        .|.nodeByLabelScan("m", "M")
        .nodeByLabelScan("n", "N")
        .build()
    )
  }

  test("Should be eager if deleted node is unstable") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .deleteNode("n")
      .apply()
      .|.allNodeScan("n")
      .unwind("[0,1] AS i")
      .argument()
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .deleteNode("n")
        .eager(ListSet(ReadDeleteConflict("n", Some(Conflict(Id(2), Id(4))))))
        .apply()
        .|.allNodeScan("n")
        .unwind("[0,1] AS i")
        .argument()
        .build()
    )
  }

  test(
    "Should be eager if deleted node is unstable, but not protect projection after DELETE that will crash regardless"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .projection("n.prop AS p")
      .deleteNode("n")
      .apply()
      .|.allNodeScan("n")
      .unwind("[0,1] AS i")
      .argument()
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .projection("n.prop AS p")
        .deleteNode("n")
        .eager(ListSet(ReadDeleteConflict("n", Some(Conflict(Id(3), Id(5))))))
        .apply()
        .|.allNodeScan("n")
        .unwind("[0,1] AS i")
        .argument()
        .build()
    )
  }

  test("Should not be eager if deleted node is stable") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .deleteNode("n")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  test("Should be eager if deleted node conflicts with unstable node") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .deleteNode("n")
      .apply()
      .|.allNodeScan("m")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .deleteNode("n")
        .eager(ListSet(ReadDeleteConflict("m", Some(Conflict(Id(2), Id(4))))))
        .apply()
        .|.allNodeScan("m")
        .allNodeScan("n")
        .build()
    )
  }

  test("Should be eager if deleted node (DeleteNode with expression) conflicts with unstable node") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .deleteNode(Head(listOf(varFor("n")))(pos))
      .apply()
      .|.allNodeScan("m")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .deleteNode(Head(listOf(varFor("n")))(pos))
        .eager(ListSet(ReadDeleteConflict("m", Some(Conflict(Id(2), Id(4))))))
        .apply()
        .|.allNodeScan("m")
        .allNodeScan("n")
        .build()
    )
  }

  test("Should be eager if deleted node (DeleteExpression) conflicts with unstable node") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .deleteExpression(Head(listOf(varFor("n")))(pos))
      .apply()
      .|.allNodeScan("m")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .deleteExpression(Head(listOf(varFor("n")))(pos))
        .eager(ListSet(ReadDeleteConflict("m", Some(Conflict(Id(2), Id(4))))))
        .apply()
        .|.allNodeScan("m")
        .allNodeScan("n")
        .build()
    )
  }

  test("Should be eager if deleted node (DetachDeleteNode with expression) conflicts with unstable node") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .detachDeleteNode("head(n)")
      .apply()
      .|.allNodeScan("m")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .detachDeleteNode("head(n)")
        .eager(ListSet(ReadDeleteConflict("m", Some(Conflict(Id(2), Id(4))))))
        .apply()
        .|.allNodeScan("m")
        .allNodeScan("n")
        .build()
    )
  }

  test("Should be eager if deleted node (DetachDeleteExpression) conflicts with unstable node") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .detachDeleteExpression("head([n])")
      .apply()
      .|.allNodeScan("m")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .detachDeleteExpression("head([n])")
        .eager(ListSet(ReadDeleteConflict("m", Some(Conflict(Id(2), Id(4))))))
        .apply()
        .|.allNodeScan("m")
        .allNodeScan("n")
        .build()
    )
  }

  test("Should be eager if deleted node conflicts with node that is introduced in Expand") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .detachDeleteNode("n")
      .expand("(n)-[r]->(m)")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .detachDeleteNode("n")
        .eager(ListSet(ReadDeleteConflict("m", Some(Conflict(Id(2), Id(3))))))
        .expand("(n)-[r]->(m)")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "Eager for DELETE conflict must be placed after the last predicate on matched node, even if higher cardinality"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .detachDeleteNode("n").withCardinality(30)
      .filter("m:M").withCardinality(30) // Filter can crash if executed on deleted node.
      .unwind("[1,2,3] AS i").withCardinality(60)
      .expand("(n)-[r]->(m)").withCardinality(20)
      .allNodeScan("n").withCardinality(10)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .detachDeleteNode("n")
        .eager(ListSet(ReadDeleteConflict("m", Some(Conflict(Id(2), Id(3))))))
        .filter("m:M")
        .unwind("[1,2,3] AS i")
        .expand("(n)-[r]->(m)")
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "Eager for DELETE conflict must be placed after the last reference to matched node, even if higher cardinality"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .detachDeleteNode("n").withCardinality(60)
      .projection("m.prop AS prop").withCardinality(60) // Property projection can crash if executed on deleted node.
      .unwind("[1,2,3] AS i").withCardinality(60)
      .expand("(n)-[r]->(m)").withCardinality(20)
      .allNodeScan("n").withCardinality(10)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .detachDeleteNode("n")
        .eager(ListSet(ReadDeleteConflict("m", Some(Conflict(Id(2), Id(3))))))
        .projection("m.prop AS prop")
        .unwind("[1,2,3] AS i")
        .expand("(n)-[r]->(m)")
        .allNodeScan("n")
        .build()
    )
  }

  test("Should be eager in Delete/Read conflict") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .apply()
      .|.nodeByLabelScan("a", "A")
      .deleteNode("n")
      .nodeByLabelScan("n", "A")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .apply()
        .|.nodeByLabelScan("a", "A")
        .eager(ListSet(ReadDeleteConflict("a", Some(Conflict(Id(4), Id(3))))))
        .deleteNode("n")
        .nodeByLabelScan("n", "A")
        .build()
    )
  }

  test("Should be eager in Delete/Read conflict, place eager before plan that introduces read node") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .filter("a.prop2 > 0").withCardinality(3)
      .filter("a.prop > 0").withCardinality(5)
      .apply().withCardinality(100)
      .|.nodeByLabelScan("a", "A").withCardinality(100)
      .deleteNode("n").withCardinality(10)
      .nodeByLabelScan("n", "A").withCardinality(10)
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .filter("a.prop2 > 0")
        .filter("a.prop > 0")
        .apply()
        .|.nodeByLabelScan("a", "A")
        .eager(ListSet(ReadDeleteConflict("a", Some(Conflict(Id(6), Id(5))))))
        .deleteNode("n")
        .nodeByLabelScan("n", "A")
        .build()
    )
  }

  test("Should be eager in Delete/Read conflict, if not overlapping but no predicates on read node leaf plan") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .filter("a:!A")
      .apply()
      .|.allNodeScan("a")
      .deleteNode("n")
      .nodeByLabelScan("n", "A")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .filter("a:!A")
        .apply()
        .|.allNodeScan("a")
        .eager(ListSet(ReadDeleteConflict("a", Some(Conflict(Id(5), Id(4))))))
        .deleteNode("n")
        .nodeByLabelScan("n", "A")
        .build()
    )
  }

// MATCH (n:N) UNION MATCH (n:N) DELETE n return count(*) as count
  test("Should be eager if deleted node conflicts with unstable node (identical plan to stable node)") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .deleteNode("n")
      .union()
      .|.nodeByLabelScan("n", "N")
      .nodeByLabelScan("n", "N")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .deleteNode("n")
        .union()
        .|.eager(ListSet(ReadDeleteConflict("n", Some(Conflict(Id(2), Id(4))))))
        .|.nodeByLabelScan("n", "N")
        .nodeByLabelScan("n", "N")
        .build()
    )
  }

  test("Should be eager if deleted node conflicts with unstable node in nested plan expression") {
    val nestedPlan = subPlanBuilderWithIdOffset()
      .expandInto("(n)-[r]->(m)")
      .allNodeScan("m", "n")
      .build()

    val nestedPlanExpression = NestedPlanExistsExpression(
      nestedPlan,
      s"EXISTS { MATCH (n)-[r]->(m) }"
    )(pos)

    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .deleteNode("n")
      .filterExpression(nestedPlanExpression)
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .deleteNode("n")
        .eager(ListSet(ReadDeleteConflict("m", Some(Conflict(Id(2), Id(3))))))
        .filterExpression(nestedPlanExpression)
        .allNodeScan("n")
        .build()
    )
  }

  test(
    "Eager for DELETE conflict must be placed after the last reference to matched node, in nested plan expression"
  ) {
    val nestedPlan = subPlanBuilderWithIdOffset()
      .projection("m.prop AS prop") // Property projection can crash if executed on deleted node.
      .argument("m")
      .build()

    val prop = varFor("prop")
    val nestedPlanExpression = NestedPlanCollectExpression(
      nestedPlan,
      prop,
      s"COLLECT { MATCH (m) RETURN m.prop AS prop }"
    )(pos)

    val planBuilder = new LogicalPlanBuilder()
      .produceResults("count")
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .detachDeleteNode("n").withCardinality(40)
      .projection(Map("props" -> nestedPlanExpression)).withCardinality(60)
      .unwind("[1,2,3] AS i").withCardinality(60)
      .expand("(n)-[r]->(m)").withCardinality(20)
      .allNodeScan("n").withCardinality(10)

    // This makes sure we do not mistake prop for a potential node
    planBuilder.newVariable(prop, CTList(CTAny))
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .detachDeleteNode("n")
        .eager(ListSet(ReadDeleteConflict("m", Some(Conflict(Id(2), Id(3))))))
        .projection(Map("props" -> nestedPlanExpression)).withCardinality(60)
        .unwind("[1,2,3] AS i")
        .expand("(n)-[r]->(m)")
        .allNodeScan("n")
        .build()
    )
  }

  // Ignored tests

  // Update LabelExpressionEvaluator to return a boolean or a set of the conflicting Labels
  ignore(
    "Should only reference the conflicting labels when there is a write of multiple labels in the same create pattern"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("d")
      .apply()
      .|.nodeByLabelScan("d", "Event")
      .foreach("x", "[1]", Seq(createPattern(Seq(createNode("e", "Event", "Place")))))
      .argument()

    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(
      new LogicalPlanBuilder()
        .produceResults("d")
        .apply()
        .|.nodeByLabelScan("d", "Event")
        .eager(ListSet(LabelReadSetConflict(labelName("Event"))))
        .foreach("x", "[1]", Seq(createPattern(Seq(createNode("e", "Event", "Place")))))
        .argument()
        .build()
    )
  }

  // This one should really be fixed before enabling the new analysis
  ignore("Should not insert an eager when the property conflict of a merge is on a stable iterator?") {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("d")
      .merge(Seq(createNodeWithProperties("n", Seq(), "{foo: 5}")))
      .filter("n.foo = 5")
      .allNodeScan("n")

    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  // No analysis for possible overlaps of node variables based on predicates yet.
  ignore(
    "does not insert eager between label set and all labels read if no overlap possible by property predicate means"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("labels")
      .projection("labels(n) AS labels")
      .setLabels("m", "A")
      .filter("m.prop = 4")
      .expand("(n)-[r]->(m)")
      .filter("n.prop = 5")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }

  // This is not very important to fix, the kind of query that yields this plan is quite unrealistic.
  ignore(
    "does not insert eager between Create and NodeByLabelScan if label overlap with labels from AntiSemiApply and from NodeByLabelScan"
  ) {
    val planBuilder = new LogicalPlanBuilder()
      .produceResults("o")
      .create(createNode("o", "M", "O"))
      .cartesianProduct()
      .|.antiSemiApply()
      .|.|.filter("m:O")
      .|.|.argument("m")
      .|.nodeByLabelScan("m", "M")
      .allNodeScan("n")
    val plan = planBuilder.build()

    val result = EagerWhereNeededRewriter(planBuilder.cardinalities, Attributes(planBuilder.idGen)).eagerize(
      plan,
      planBuilder.getSemanticTable
    )
    result should equal(plan)
  }
}
