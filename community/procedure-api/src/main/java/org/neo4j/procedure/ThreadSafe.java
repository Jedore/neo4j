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
package org.neo4j.procedure;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation marks a {@link Procedure}, {@link UserFunction}, or {@link UserAggregationFunction} as thread-safe,
 * i.e. that its implementation is guaranteed safe to be called concurrently by different worker threads during query
 * execution.
 * Providing this guarantee is a necessary requirement for allowing it to be used in a query that is executed with the
 * parallel runtime.
 * <p>
 * NOTE: The guarantee also entails that it cannot interact with the transaction or the database through the Core
 * API, as this is currently not a thread-safe API. This holds even when those APIs are populated in fields
 * annotated with {@link Context}.
 * Failure to uphold the guarantee by the implementation could result in failures, incorrect results and undefined
 * behavior during query execution.
 * <p>
 * NOTE: Even when this annotation is present, there is currently no guarantee that executing a
 * procedure or function will actually be supported by the parallel runtime.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ThreadSafe {}
