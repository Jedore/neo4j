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
package org.neo4j.cypher.internal.compiler.planner.logical.steps

import org.neo4j.cypher.internal.compiler.helpers.PropertyAccessHelper.PropertyAccess
import org.neo4j.cypher.internal.compiler.helpers.SeqSupport.RichSeq
import org.neo4j.cypher.internal.compiler.planner.logical.LeafPlanFinder
import org.neo4j.cypher.internal.compiler.planner.logical.LogicalPlanningContext
import org.neo4j.cypher.internal.compiler.planner.logical.QueryPlannerConfiguration
import org.neo4j.cypher.internal.compiler.planner.logical.SelectorHeuristic
import org.neo4j.cypher.internal.compiler.planner.logical.SortPlanner
import org.neo4j.cypher.internal.compiler.planner.logical.idp.BestResults
import org.neo4j.cypher.internal.compiler.planner.logical.ordering.InterestingOrderConfig
import org.neo4j.cypher.internal.ir.QueryGraph
import org.neo4j.cypher.internal.logical.plans.DirectedRelationshipIndexContainsScan
import org.neo4j.cypher.internal.logical.plans.DirectedRelationshipIndexEndsWithScan
import org.neo4j.cypher.internal.logical.plans.IndexedProperty
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.logical.plans.NodeByLabelScan
import org.neo4j.cypher.internal.logical.plans.NodeIndexContainsScan
import org.neo4j.cypher.internal.logical.plans.NodeIndexEndsWithScan
import org.neo4j.cypher.internal.logical.plans.NodeIndexLeafPlan
import org.neo4j.cypher.internal.logical.plans.RelationshipIndexLeafPlan
import org.neo4j.cypher.internal.logical.plans.RelationshipTypeScan
import org.neo4j.cypher.internal.logical.plans.Selection
import org.neo4j.cypher.internal.logical.plans.UndirectedRelationshipIndexContainsScan
import org.neo4j.cypher.internal.logical.plans.UndirectedRelationshipIndexEndsWithScan
import org.neo4j.cypher.internal.planner.spi.IndexDescriptor
import org.neo4j.cypher.internal.planner.spi.IndexDescriptor.IndexType

import scala.annotation.tailrec

object leafPlanOptions extends LeafPlanFinder {

  override def apply(
    config: QueryPlannerConfiguration,
    queryGraph: QueryGraph,
    interestingOrderConfig: InterestingOrderConfig,
    context: LogicalPlanningContext
  ): Iterable[BestPlans] = {
    val queryPlannerKit = config.toKit(interestingOrderConfig, context)
    val pickBest = config.pickBestCandidate(context)

    val leafPlanCandidates =
      config.leafPlanners.candidates(queryGraph, interestingOrderConfig = interestingOrderConfig, context = context)
    val leafPlanCandidatesWithSelections = queryPlannerKit.select(leafPlanCandidates, queryGraph)

    val bestPlansPerAvailableSymbols =
      leafPlanCandidatesWithSelections
        .toSeq
        .sequentiallyGroupBy(_.availableSymbols.intersect(queryGraph.idsWithoutOptionalMatchesOrUpdates))
        .map { case (_, bucket) =>
          val bestPlan = pickBest(
            bucket,
            leafPlanHeuristic(context),
            s"leaf plan with available symbols ${bucket.head.availableSymbols.map(s => s"'$s'").mkString(", ")}"
          ).get

          if (interestingOrderConfig.orderToSolve.requiredOrderCandidate.nonEmpty) {
            val sortedLeaves =
              bucket.flatMap(plan => SortPlanner.planIfAsSortedAsPossible(plan, interestingOrderConfig, context))
            val bestSortedPlan = pickBest(
              sortedLeaves,
              s"sorted leaf plan with available symbols ${bucket.head.availableSymbols.map(s => s"'$s'").mkString(", ")}"
            )
            BestResults(bestPlan, bestSortedPlan)
          } else {
            BestResults(bestPlan, None)
          }
        }

    bestPlansPerAvailableSymbols
  }

  def leafPlanHeuristic(context: LogicalPlanningContext): SelectorHeuristic = new SelectorHeuristic {

    @tailrec
    override def tieBreaker(plan: LogicalPlan): Int = plan match {
      case s: Selection => tieBreaker(s.source)
      case p: NodeIndexLeafPlan if hasAggregatingProperties(p.idName, p.properties, context) =>
        30 + indexTypeModifier(p, p.indexType)
      case p: RelationshipIndexLeafPlan if hasAggregatingProperties(p.idName, p.properties, context) =>
        30 + indexTypeModifier(p, p.indexType)
      case p: NodeIndexLeafPlan if hasAccessedProperties(p.idName, p.properties, context) =>
        20 + indexTypeModifier(p, p.indexType)
      case p: RelationshipIndexLeafPlan if hasAccessedProperties(p.idName, p.properties, context) =>
        20 + indexTypeModifier(p, p.indexType)
      case _: NodeByLabelScan      => 10
      case _: RelationshipTypeScan => 10
      case _                       => 0
    }

    private def indexTypeModifier(plan: LogicalPlan, indexType: org.neo4j.graphdb.schema.IndexType): Int = {
      plan match {
        // Prefer TEXT index for ENDS WITH and CONTAINS
        case _: NodeIndexEndsWithScan |
          _: DirectedRelationshipIndexEndsWithScan |
          _: UndirectedRelationshipIndexEndsWithScan |
          _: NodeIndexContainsScan |
          _: DirectedRelationshipIndexContainsScan |
          _: UndirectedRelationshipIndexContainsScan =>
          IndexDescriptor.IndexType.fromPublicApi(indexType).fold(0) {
            case IndexType.Point => 0
            case IndexType.Range => 0
            case IndexType.Text  => 2
          }

        // Prefer POINT index for all compatible queries
        // Prefer Range index for other queries
        case _ =>
          IndexDescriptor.IndexType.fromPublicApi(indexType).fold(0) {
            case IndexType.Point => 3
            case IndexType.Range => 2
            case IndexType.Text  => 0
          }
      }
    }
  }

  private def hasAggregatingProperties(
    varName: String,
    properties: Seq[IndexedProperty],
    context: LogicalPlanningContext
  ): Boolean =
    properties.exists(prop =>
      context.plannerState.indexCompatiblePredicatesProviderContext.aggregatingProperties.contains(PropertyAccess(
        varName,
        prop.propertyKeyToken.name
      ))
    )

  private def hasAccessedProperties(
    varName: String,
    properties: Seq[IndexedProperty],
    context: LogicalPlanningContext
  ): Boolean =
    properties.exists(prop =>
      context.plannerState.accessedProperties.contains(PropertyAccess(varName, prop.propertyKeyToken.name))
    )

}
