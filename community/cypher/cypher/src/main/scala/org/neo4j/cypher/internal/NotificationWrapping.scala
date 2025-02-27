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
package org.neo4j.cypher.internal

import org.neo4j.common.EntityType
import org.neo4j.cypher.internal.ast.UsingAnyIndexType
import org.neo4j.cypher.internal.ast.UsingPointIndexType
import org.neo4j.cypher.internal.ast.UsingRangeIndexType
import org.neo4j.cypher.internal.ast.UsingTextIndexType
import org.neo4j.cypher.internal.compiler.CodeGenerationFailedNotification
import org.neo4j.cypher.internal.compiler.DeprecatedFieldNotification
import org.neo4j.cypher.internal.compiler.DeprecatedProcedureNotification
import org.neo4j.cypher.internal.compiler.EagerLoadCsvNotification
import org.neo4j.cypher.internal.compiler.ExhaustiveShortestPathForbiddenNotification
import org.neo4j.cypher.internal.compiler.ExperimentalFeatureNotification
import org.neo4j.cypher.internal.compiler.IndexHintUnfulfillableNotification
import org.neo4j.cypher.internal.compiler.JoinHintUnfulfillableNotification
import org.neo4j.cypher.internal.compiler.LargeLabelWithLoadCsvNotification
import org.neo4j.cypher.internal.compiler.MissingLabelNotification
import org.neo4j.cypher.internal.compiler.MissingParametersNotification
import org.neo4j.cypher.internal.compiler.MissingPropertyNameNotification
import org.neo4j.cypher.internal.compiler.MissingRelTypeNotification
import org.neo4j.cypher.internal.compiler.NodeIndexLookupUnfulfillableNotification
import org.neo4j.cypher.internal.compiler.ProcedureWarningNotification
import org.neo4j.cypher.internal.compiler.RelationshipIndexLookupUnfulfillableNotification
import org.neo4j.cypher.internal.compiler.RuntimeUnsupportedNotification
import org.neo4j.cypher.internal.compiler.SuboptimalIndexForConstainsQueryNotification
import org.neo4j.cypher.internal.compiler.SuboptimalIndexForEndsWithQueryNotification
import org.neo4j.cypher.internal.util.CartesianProductNotification
import org.neo4j.cypher.internal.util.DeprecatedDatabaseNameNotification
import org.neo4j.cypher.internal.util.DeprecatedFunctionNotification
import org.neo4j.cypher.internal.util.DeprecatedNodesOrRelationshipsInSetClauseNotification
import org.neo4j.cypher.internal.util.DeprecatedRelTypeSeparatorNotification
import org.neo4j.cypher.internal.util.DeprecatedRuntimeNotification
import org.neo4j.cypher.internal.util.DeprecatedTextIndexProvider
import org.neo4j.cypher.internal.util.FixedLengthRelationshipInShortestPath
import org.neo4j.cypher.internal.util.HomeDatabaseNotPresent
import org.neo4j.cypher.internal.util.InputPosition
import org.neo4j.cypher.internal.util.InternalNotification
import org.neo4j.cypher.internal.util.RepeatedRelationshipReference
import org.neo4j.cypher.internal.util.RepeatedVarLengthRelationshipReference
import org.neo4j.cypher.internal.util.SubqueryVariableShadowing
import org.neo4j.cypher.internal.util.UnboundedShortestPathNotification
import org.neo4j.cypher.internal.util.UnionReturnItemsInDifferentOrder
import org.neo4j.cypher.internal.util.UnsatisfiableRelationshipTypeExpression
import org.neo4j.graphdb
import org.neo4j.graphdb.impl.notification.NotificationCodeWithDescription
import org.neo4j.graphdb.impl.notification.NotificationDetail

import scala.jdk.CollectionConverters.SeqHasAsJava
import scala.jdk.CollectionConverters.SetHasAsJava

object NotificationWrapping {

  def asKernelNotification(offset: Option[InputPosition])(notification: InternalNotification)
    : NotificationCodeWithDescription#Notification = notification match {
    case CartesianProductNotification(pos, variables) =>
      NotificationCodeWithDescription.CARTESIAN_PRODUCT.notification(
        pos.withOffset(offset).asInputPosition,
        NotificationDetail.Factory.cartesianProduct(variables.asJava)
      )
    case RuntimeUnsupportedNotification(msg) =>
      NotificationCodeWithDescription.RUNTIME_UNSUPPORTED.notification(
        graphdb.InputPosition.empty,
        NotificationDetail.Factory.message("Runtime unsupported", msg)
      )
    case IndexHintUnfulfillableNotification(variableName, label, propertyKeys, entityType, indexType) =>
      val detail = entityType match {
        case EntityType.NODE => indexType match {
            case UsingAnyIndexType   => NotificationDetail.Factory.nodeAnyIndex(variableName, label, propertyKeys: _*)
            case UsingTextIndexType  => NotificationDetail.Factory.nodeTextIndex(variableName, label, propertyKeys: _*)
            case UsingRangeIndexType => NotificationDetail.Factory.nodeRangeIndex(variableName, label, propertyKeys: _*)
            case UsingPointIndexType => NotificationDetail.Factory.nodePointIndex(variableName, label, propertyKeys: _*)
          }
        case EntityType.RELATIONSHIP => indexType match {
            case UsingAnyIndexType =>
              NotificationDetail.Factory.relationshipAnyIndex(variableName, label, propertyKeys: _*)
            case UsingTextIndexType =>
              NotificationDetail.Factory.relationshipTextIndex(variableName, label, propertyKeys: _*)
            case UsingRangeIndexType =>
              NotificationDetail.Factory.relationshipRangeIndex(variableName, label, propertyKeys: _*)
            case UsingPointIndexType =>
              NotificationDetail.Factory.relationshipPointIndex(variableName, label, propertyKeys: _*)
          }
      }
      NotificationCodeWithDescription.INDEX_HINT_UNFULFILLABLE.notification(graphdb.InputPosition.empty, detail)
    case JoinHintUnfulfillableNotification(variables) =>
      NotificationCodeWithDescription.JOIN_HINT_UNFULFILLABLE.notification(
        graphdb.InputPosition.empty,
        NotificationDetail.Factory.joinKey(variables.asJava)
      )
    case NodeIndexLookupUnfulfillableNotification(labels) =>
      NotificationCodeWithDescription.INDEX_LOOKUP_FOR_DYNAMIC_PROPERTY.notification(
        graphdb.InputPosition.empty,
        NotificationDetail.Factory.nodeIndexSeekOrScan(labels.asJava)
      )
    case RelationshipIndexLookupUnfulfillableNotification(relTypes) =>
      NotificationCodeWithDescription.INDEX_LOOKUP_FOR_DYNAMIC_PROPERTY.notification(
        graphdb.InputPosition.empty,
        NotificationDetail.Factory.relationshipIndexSeekOrScan(relTypes.asJava)
      )
    case EagerLoadCsvNotification =>
      NotificationCodeWithDescription.EAGER_LOAD_CSV.notification(graphdb.InputPosition.empty)
    case LargeLabelWithLoadCsvNotification =>
      NotificationCodeWithDescription.LARGE_LABEL_LOAD_CSV.notification(graphdb.InputPosition.empty)
    case MissingLabelNotification(pos, label) =>
      NotificationCodeWithDescription.MISSING_LABEL.notification(
        pos.withOffset(offset).asInputPosition,
        NotificationDetail.Factory.label(label)
      )
    case MissingRelTypeNotification(pos, relType) =>
      NotificationCodeWithDescription.MISSING_REL_TYPE.notification(
        pos.withOffset(offset).asInputPosition,
        NotificationDetail.Factory.relationshipType(relType)
      )
    case MissingPropertyNameNotification(pos, name) =>
      NotificationCodeWithDescription.MISSING_PROPERTY_NAME.notification(
        pos.withOffset(offset).asInputPosition,
        NotificationDetail.Factory.propertyName(name)
      )
    case UnboundedShortestPathNotification(pos) =>
      NotificationCodeWithDescription.UNBOUNDED_SHORTEST_PATH.notification(pos.withOffset(offset).asInputPosition)
    case ExhaustiveShortestPathForbiddenNotification(pos) =>
      NotificationCodeWithDescription.EXHAUSTIVE_SHORTEST_PATH.notification(pos.withOffset(offset).asInputPosition)
    case DeprecatedFunctionNotification(pos, oldName, newName) =>
      NotificationCodeWithDescription.DEPRECATED_FUNCTION.notification(
        pos.withOffset(offset).asInputPosition,
        NotificationDetail.Factory.deprecatedName(oldName, newName)
      )
    case DeprecatedProcedureNotification(pos, oldName, newName) =>
      NotificationCodeWithDescription.DEPRECATED_PROCEDURE.notification(
        pos.withOffset(offset).asInputPosition,
        NotificationDetail.Factory.deprecatedName(oldName, newName)
      )
    case DeprecatedFieldNotification(pos, procedure, field) =>
      NotificationCodeWithDescription.DEPRECATED_PROCEDURE_RETURN_FIELD.notification(
        pos.withOffset(offset).asInputPosition,
        NotificationDetail.Factory.deprecatedField(procedure, field)
      )
    case DeprecatedRelTypeSeparatorNotification(pos, rewrittenExpression) =>
      NotificationCodeWithDescription.DEPRECATED_RELATIONSHIP_TYPE_SEPARATOR.notification(
        pos.withOffset(offset).asInputPosition,
        NotificationDetail.Factory.deprecationNotificationDetail(rewrittenExpression)
      )
    case DeprecatedNodesOrRelationshipsInSetClauseNotification(pos) =>
      NotificationCodeWithDescription.DEPRECATED_NODE_OR_RELATIONSHIP_ON_RHS_SET_CLAUSE.notification(
        pos.withOffset(offset).asInputPosition
      )
    case ProcedureWarningNotification(pos, name, warning) =>
      NotificationCodeWithDescription.PROCEDURE_WARNING.notification(
        pos.withOffset(offset).asInputPosition,
        NotificationDetail.Factory.procedureWarning(name, warning)
      )
    case ExperimentalFeatureNotification(msg) =>
      NotificationCodeWithDescription.RUNTIME_EXPERIMENTAL.notification(
        graphdb.InputPosition.empty,
        NotificationDetail.Factory.message("PARALLEL", msg)
      )
    case SuboptimalIndexForConstainsQueryNotification(variableName, label, propertyKeys, entityType) =>
      val detail = entityType match {
        case EntityType.NODE => NotificationDetail.Factory.nodeAnyIndex(variableName, label, propertyKeys: _*)
        case EntityType.RELATIONSHIP =>
          NotificationDetail.Factory.relationshipAnyIndex(variableName, label, propertyKeys: _*)
      }
      NotificationCodeWithDescription.SUBOPTIMAL_INDEX_FOR_CONTAINS_QUERY.notification(
        graphdb.InputPosition.empty,
        detail
      )
    case SuboptimalIndexForEndsWithQueryNotification(variableName, label, propertyKeys, entityType) =>
      val detail = entityType match {
        case EntityType.NODE => NotificationDetail.Factory.nodeAnyIndex(variableName, label, propertyKeys: _*)
        case EntityType.RELATIONSHIP =>
          NotificationDetail.Factory.relationshipAnyIndex(variableName, label, propertyKeys: _*)
      }
      NotificationCodeWithDescription.SUBOPTIMAL_INDEX_FOR_ENDS_WITH_QUERY.notification(
        graphdb.InputPosition.empty,
        detail
      )
    case MissingParametersNotification(names) =>
      NotificationCodeWithDescription.MISSING_PARAMETERS_FOR_EXPLAIN.notification(
        graphdb.InputPosition.empty,
        NotificationDetail.Factory.message(
          "Explain with missing parameters",
          names.mkString("Missing parameters: ", ", ", "")
        )
      )
    case CodeGenerationFailedNotification(msg) =>
      NotificationCodeWithDescription.CODE_GENERATION_FAILED.notification(
        graphdb.InputPosition.empty,
        NotificationDetail.Factory.message("Error from code generation", msg)
      )
    case SubqueryVariableShadowing(pos, varName) =>
      NotificationCodeWithDescription.SUBQUERY_VARIABLE_SHADOWING.notification(
        pos.withOffset(offset).asInputPosition,
        NotificationDetail.Factory.shadowingVariable(varName)
      )
    case UnionReturnItemsInDifferentOrder(pos) =>
      NotificationCodeWithDescription.UNION_RETURN_ORDER.notification(
        pos.withOffset(offset).asInputPosition
      )
    case HomeDatabaseNotPresent(name) => NotificationCodeWithDescription.HOME_DATABASE_NOT_PRESENT.notification(
        InputPosition.NONE.asInputPosition,
        NotificationDetail.Factory.message("Not Found", s"HOME DATABASE: $name")
      )
    case FixedLengthRelationshipInShortestPath(pos) =>
      NotificationCodeWithDescription.DEPRECATED_SHORTEST_PATH_WITH_FIXED_LENGTH_RELATIONSHIP.notification(
        pos.withOffset(offset).asInputPosition
      )

    case DeprecatedTextIndexProvider(pos) =>
      NotificationCodeWithDescription.DEPRECATED_TEXT_INDEX_PROVIDER.notification(
        pos.withOffset(offset).asInputPosition
      )

    case DeprecatedDatabaseNameNotification(name, pos) =>
      NotificationCodeWithDescription.DEPRECATED_DATABASE_NAME.notification(
        pos.map(_.withOffset(offset).asInputPosition).getOrElse(graphdb.InputPosition.empty),
        NotificationDetail.Factory.message("Deprecated name", s"Name: $name")
      )

    case DeprecatedRuntimeNotification(msg) =>
      NotificationCodeWithDescription.DEPRECATED_RUNTIME_OPTION.notification(
        graphdb.InputPosition.empty,
        NotificationDetail.Factory.message("Deprecated runtime option", msg)
      )

    case UnsatisfiableRelationshipTypeExpression(position, relTypeExpression) =>
      NotificationCodeWithDescription.UNSATISFIABLE_RELATIONSHIP_TYPE_EXPRESSION.notification(
        position.withOffset(offset).asInputPosition,
        NotificationDetail.Factory.unsatisfiableRelTypeExpression(relTypeExpression)
      )

    case RepeatedRelationshipReference(position, relName) =>
      NotificationCodeWithDescription.REPEATED_RELATIONSHIP_REFERENCE.notification(
        position.withOffset(offset).asInputPosition,
        NotificationDetail.Factory.repeatedRelationship(relName)
      )

    case RepeatedVarLengthRelationshipReference(position, relName) =>
      NotificationCodeWithDescription.REPEATED_VAR_LENGTH_RELATIONSHIP_REFERENCE.notification(
        position.withOffset(offset).asInputPosition,
        NotificationDetail.Factory.repeatedVarLengthRel(relName)
      )

    case _ => throw new IllegalStateException("Missing mapping for notification detail.")
  }

  implicit private class ConvertibleCompilerInputPosition(pos: InputPosition) {
    def asInputPosition = new graphdb.InputPosition(pos.offset, pos.line, pos.column)
  }
}
