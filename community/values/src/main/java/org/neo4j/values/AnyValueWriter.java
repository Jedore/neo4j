/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.values;

import org.neo4j.values.virtual.CoordinateReferenceSystem;

/**
 * Writer of any values.
 */
public interface AnyValueWriter<E extends Exception> extends ValueWriter<E>
{

    void writeNodeReference( long nodeId ) throws E;

    void beginLabels( int numberOfLabels ) throws E;

    void writeLabel( int labelId ) throws E;

    void endLabels() throws E;

    void writeEdgeReference( long edgeId ) throws E;

    void beginMap( int size ) throws E;

    void writeKeyId( int keyId ) throws E;

    void endMap() throws E;

    void beginList( int size ) throws E;

    void endList() throws E;

    void beginPath( int length ) throws E;

    void endPath() throws E;

    void beginPoint( CoordinateReferenceSystem coordinateReferenceSystem ) throws E;

    void endPoint() throws E;
}
