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
package org.neo4j.cypher.internal.ir

import org.neo4j.cypher.internal.ast.CommandClause
import org.neo4j.cypher.internal.ast.Hint
import org.neo4j.cypher.internal.ast.TransactionsCommandClause
import org.neo4j.cypher.internal.expressions.Expression

case class CommandProjection(clause: CommandClause) extends QueryHorizon {

  override def exposedSymbols(coveredIds: Set[String]): Set[String] = {
    val columnNames = clause match {
      case t: TransactionsCommandClause if t.yieldItems.nonEmpty =>
        t.yieldItems.map(_.aliasedVariable.name)
      case _ => clause.unfilteredColumns.columns.map(_.name)
    }
    coveredIds ++ columnNames
  }

  override def dependingExpressions: Seq[Expression] = Seq()

  override def allHints: Set[Hint] = Set.empty

  override def withoutHints(hintsToIgnore: Set[Hint]): QueryHorizon = this

  override def isTerminatingProjection: Boolean = false
}
