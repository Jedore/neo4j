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
package org.neo4j.cypher.internal.compiler.planner.logical.plans

import org.neo4j.cypher.internal.ast.AstConstructionTestSupport
import org.neo4j.cypher.internal.compiler.planner.LogicalPlanningTestSupport2
import org.neo4j.cypher.internal.compiler.planner.StubbedLogicalPlanningConfiguration
import org.neo4j.cypher.internal.compiler.planner.logical.ExpressionEvaluator
import org.neo4j.cypher.internal.compiler.planner.logical.LogicalPlanningContext
import org.neo4j.cypher.internal.compiler.planner.logical.LogicalPlanningContext.Settings
import org.neo4j.cypher.internal.compiler.planner.logical.LogicalPlanningContext.StaticComponents
import org.neo4j.cypher.internal.compiler.planner.logical.ordering.InterestingOrderConfig
import org.neo4j.cypher.internal.compiler.planner.logical.steps.LogicalPlanProducer
import org.neo4j.cypher.internal.compiler.planner.logical.steps.mergeRelationshipUniqueIndexSeekLeafPlanner
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.PropertyKeyToken
import org.neo4j.cypher.internal.expressions.RELATIONSHIP_TYPE
import org.neo4j.cypher.internal.expressions.RelationshipTypeToken
import org.neo4j.cypher.internal.expressions.SemanticDirection
import org.neo4j.cypher.internal.ir.PatternRelationship
import org.neo4j.cypher.internal.ir.QueryGraph
import org.neo4j.cypher.internal.ir.Selections
import org.neo4j.cypher.internal.ir.SimplePatternLength
import org.neo4j.cypher.internal.logical.plans.AssertSameRelationship
import org.neo4j.cypher.internal.logical.plans.CanGetValue
import org.neo4j.cypher.internal.logical.plans.CompositeQueryExpression
import org.neo4j.cypher.internal.logical.plans.DirectedRelationshipUniqueIndexSeek
import org.neo4j.cypher.internal.logical.plans.IndexOrderNone
import org.neo4j.cypher.internal.logical.plans.IndexedProperty
import org.neo4j.cypher.internal.logical.plans.SingleQueryExpression
import org.neo4j.cypher.internal.options.CypherDebugOptions
import org.neo4j.cypher.internal.planner.spi.PlanningAttributes
import org.neo4j.cypher.internal.util.AnonymousVariableNameGenerator
import org.neo4j.cypher.internal.util.CancellationChecker
import org.neo4j.cypher.internal.util.devNullLogger
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite
import org.neo4j.graphdb.schema.IndexType

class MergeRelationshipUniqueIndexSeekLeafPlanningTest
    extends CypherFunSuite
    with LogicalPlanningTestSupport2
    with AstConstructionTestSupport {

  private val relationshipName = "r"
  private val relationshipTypeName = "REL"
  private val startNodeName = "start"
  private val endNodeName = "end"
  private val prop = "prop"
  private val prop2 = "prop2"
  private val prop3 = "prop3"

  private val rProp = prop(relationshipName, prop)
  private val rProp2 = prop(relationshipName, prop2)
  private val rProp3 = prop(relationshipName, prop3)
  private val lit42 = literalInt(42)
  private val lit6 = literalInt(6)
  private val litFoo = literalString("Foo")

  private val rPropInLit42 = in(rProp, listOf(lit42))
  private val rProp2InLit6 = in(rProp2, listOf(lit6))
  private val rProp3InLitFoo = in(rProp3, listOf(litFoo))

  private def buildQueryGraph(predicates: Expression*) =
    QueryGraph(
      selections = Selections.from(predicates),
      patternRelationships = Set(PatternRelationship(
        relationshipName,
        (startNodeName, endNodeName),
        SemanticDirection.OUTGOING,
        List(relTypeName(relationshipTypeName)),
        SimplePatternLength
      ))
    )

  private def buildLogicalPlanningContext(
    config: StubbedLogicalPlanningConfiguration,
    planningMergeRelationshipUniqueIndexSeekEnabled: Boolean
  ): LogicalPlanningContext = {
    val metrics = config.metricsFactory.newMetrics(config.planContext, mock[ExpressionEvaluator], config.executionModel)

    val planningAttributes = PlanningAttributes.newAttributes

    val logicalPlanProducer = LogicalPlanProducer(metrics.cardinality, planningAttributes, idGen)

    val staticComponents = StaticComponents(
      planContext = config.planContext,
      notificationLogger = devNullLogger,
      planningAttributes = planningAttributes,
      logicalPlanProducer = logicalPlanProducer,
      queryGraphSolver = queryGraphSolver,
      metrics = metrics,
      idGen = idGen,
      anonymousVariableNameGenerator = new AnonymousVariableNameGenerator(),
      cancellationChecker = CancellationChecker.NeverCancelled,
      semanticTable = config.semanticTable
    )

    val settings = Settings(
      executionModel = config.executionModel,
      debugOptions = CypherDebugOptions.default,
      predicatesAsUnionMaxSize = cypherCompilerConfig.predicatesAsUnionMaxSize(),
      planningRelationshipUniqueIndexSeekEnabled = true,
      planningMergeRelationshipUniqueIndexSeekEnabled = planningMergeRelationshipUniqueIndexSeekEnabled
    )

    LogicalPlanningContext(staticComponents, settings)
  }

  test("does not plan index seek when planning relationship unique index seek for merger is disabled") {
    // given
    val config = new given {
      uniqueRelationshipIndexOn(relationshipTypeName, prop)
    }
    val context = buildLogicalPlanningContext(config, planningMergeRelationshipUniqueIndexSeekEnabled = false)
    val queryGraph = buildQueryGraph(rPropInLit42)

    // when
    val plans = mergeRelationshipUniqueIndexSeekLeafPlanner(queryGraph, InterestingOrderConfig.empty, context)

    // then
    plans shouldBe empty
  }

  test("does not plan index seek when no index is present") {
    // given
    val config = new given()
    val context = buildLogicalPlanningContext(config, planningMergeRelationshipUniqueIndexSeekEnabled = true)
    val queryGraph = buildQueryGraph(rPropInLit42)

    // when
    val plans = mergeRelationshipUniqueIndexSeekLeafPlanner(queryGraph, InterestingOrderConfig.empty, context)

    // then
    plans shouldBe empty
  }

  test("plans an index seek on a single property") {
    // given
    val config = new given {
      uniqueRelationshipIndexOn(relationshipTypeName, prop)
    }
    val context = buildLogicalPlanningContext(config, planningMergeRelationshipUniqueIndexSeekEnabled = true)
    val queryGraph = buildQueryGraph(rPropInLit42)

    // when
    val plans = mergeRelationshipUniqueIndexSeekLeafPlanner(queryGraph, InterestingOrderConfig.empty, context)

    // then
    plans shouldEqual Set(DirectedRelationshipUniqueIndexSeek(
      idName = relationshipName,
      startNode = startNodeName,
      endNode = endNodeName,
      typeToken =
        RelationshipTypeToken(relationshipTypeName, config.semanticTable.resolvedRelTypeNames(relationshipTypeName)),
      properties = Seq(IndexedProperty(
        PropertyKeyToken(prop, config.semanticTable.resolvedPropertyKeyNames(prop)),
        CanGetValue,
        RELATIONSHIP_TYPE
      )),
      valueExpr = SingleQueryExpression(lit42),
      argumentIds = Set.empty,
      indexOrder = IndexOrderNone,
      indexType = IndexType.RANGE
    ))
  }

  test(
    "plans two index seek, with an assert same relationship, when querying two properties with two different indexes"
  ) {
    // given
    val config = new given {
      uniqueRelationshipIndexOn(relationshipTypeName, prop)
      uniqueRelationshipIndexOn(relationshipTypeName, prop2)
    }
    val context = buildLogicalPlanningContext(config, planningMergeRelationshipUniqueIndexSeekEnabled = true)
    val queryGraph = buildQueryGraph(rPropInLit42, rProp2InLit6)

    // when
    val plans = mergeRelationshipUniqueIndexSeekLeafPlanner(queryGraph, InterestingOrderConfig.empty, context)

    val lhs = DirectedRelationshipUniqueIndexSeek(
      idName = relationshipName,
      startNode = startNodeName,
      endNode = endNodeName,
      typeToken =
        RelationshipTypeToken(relationshipTypeName, config.semanticTable.resolvedRelTypeNames(relationshipTypeName)),
      properties = Seq(IndexedProperty(
        PropertyKeyToken(prop, config.semanticTable.resolvedPropertyKeyNames(prop)),
        CanGetValue,
        RELATIONSHIP_TYPE
      )),
      valueExpr = SingleQueryExpression(lit42),
      argumentIds = Set.empty,
      indexOrder = IndexOrderNone,
      indexType = IndexType.RANGE
    )

    val rhs = DirectedRelationshipUniqueIndexSeek(
      idName = relationshipName,
      startNode = startNodeName,
      endNode = endNodeName,
      typeToken =
        RelationshipTypeToken(relationshipTypeName, config.semanticTable.resolvedRelTypeNames(relationshipTypeName)),
      properties = Seq(IndexedProperty(
        PropertyKeyToken(prop2, config.semanticTable.resolvedPropertyKeyNames(prop2)),
        CanGetValue,
        RELATIONSHIP_TYPE
      )),
      valueExpr = SingleQueryExpression(lit6),
      argumentIds = Set.empty,
      indexOrder = IndexOrderNone,
      indexType = IndexType.RANGE
    )

    // then
    plans shouldEqual Set(AssertSameRelationship(idName = relationshipName, left = lhs, right = rhs))
  }

  test("plans a single index seek when querying two properties with a composite index") {
    // given
    val config = new given {
      uniqueRelationshipIndexOn(relationshipTypeName, prop, prop2)
    }
    val context = buildLogicalPlanningContext(config, planningMergeRelationshipUniqueIndexSeekEnabled = true)
    val queryGraph = buildQueryGraph(rPropInLit42, rProp2InLit6)

    // when
    val plans = mergeRelationshipUniqueIndexSeekLeafPlanner(queryGraph, InterestingOrderConfig.empty, context)

    // then
    plans shouldEqual Set(DirectedRelationshipUniqueIndexSeek(
      idName = relationshipName,
      startNode = startNodeName,
      endNode = endNodeName,
      typeToken =
        RelationshipTypeToken(relationshipTypeName, config.semanticTable.resolvedRelTypeNames(relationshipTypeName)),
      properties = Seq(
        IndexedProperty(
          PropertyKeyToken(prop, config.semanticTable.resolvedPropertyKeyNames(prop)),
          CanGetValue,
          RELATIONSHIP_TYPE
        ),
        IndexedProperty(
          PropertyKeyToken(prop2, config.semanticTable.resolvedPropertyKeyNames(prop2)),
          CanGetValue,
          RELATIONSHIP_TYPE
        )
      ),
      valueExpr = CompositeQueryExpression(List(SingleQueryExpression(lit42), SingleQueryExpression(lit6))),
      argumentIds = Set.empty,
      indexOrder = IndexOrderNone,
      indexType = IndexType.RANGE
    ))
  }

  test("plans a single index seek and a composite one under an assert same relationship") {
    // given
    val config = new given {
      uniqueRelationshipIndexOn(relationshipTypeName, prop, prop2)
      uniqueRelationshipIndexOn(relationshipTypeName, prop3)
    }
    val context = buildLogicalPlanningContext(config, planningMergeRelationshipUniqueIndexSeekEnabled = true)
    val queryGraph = buildQueryGraph(rPropInLit42, rProp2InLit6, rProp3InLitFoo)

    // when
    val plans = mergeRelationshipUniqueIndexSeekLeafPlanner(queryGraph, InterestingOrderConfig.empty, context)

    val lhs = DirectedRelationshipUniqueIndexSeek(
      idName = relationshipName,
      startNode = startNodeName,
      endNode = endNodeName,
      typeToken =
        RelationshipTypeToken(relationshipTypeName, config.semanticTable.resolvedRelTypeNames(relationshipTypeName)),
      properties = Seq(
        IndexedProperty(
          PropertyKeyToken(prop, config.semanticTable.resolvedPropertyKeyNames(prop)),
          CanGetValue,
          RELATIONSHIP_TYPE
        ),
        IndexedProperty(
          PropertyKeyToken(prop2, config.semanticTable.resolvedPropertyKeyNames(prop2)),
          CanGetValue,
          RELATIONSHIP_TYPE
        )
      ),
      valueExpr = CompositeQueryExpression(List(SingleQueryExpression(lit42), SingleQueryExpression(lit6))),
      argumentIds = Set.empty,
      indexOrder = IndexOrderNone,
      indexType = IndexType.RANGE
    )

    val rhs = DirectedRelationshipUniqueIndexSeek(
      idName = relationshipName,
      startNode = startNodeName,
      endNode = endNodeName,
      typeToken =
        RelationshipTypeToken(relationshipTypeName, config.semanticTable.resolvedRelTypeNames(relationshipTypeName)),
      properties = Seq(IndexedProperty(
        PropertyKeyToken(prop3, config.semanticTable.resolvedPropertyKeyNames(prop3)),
        CanGetValue,
        RELATIONSHIP_TYPE
      )),
      valueExpr = SingleQueryExpression(litFoo),
      argumentIds = Set.empty,
      indexOrder = IndexOrderNone,
      indexType = IndexType.RANGE
    )

    // then
    plans shouldEqual Set(AssertSameRelationship(idName = relationshipName, left = lhs, right = rhs))
  }
}
