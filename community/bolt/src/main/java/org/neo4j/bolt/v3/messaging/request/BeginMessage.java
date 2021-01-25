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
package org.neo4j.bolt.v3.messaging.request;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.neo4j.bolt.messaging.BoltIOException;
import org.neo4j.bolt.messaging.RequestMessage;
import org.neo4j.bolt.v1.runtime.bookmarking.Bookmark;
import org.neo4j.values.virtual.MapValue;
import org.neo4j.values.virtual.VirtualValues;

import static java.util.Objects.requireNonNull;
import static org.neo4j.bolt.v3.messaging.request.MessageMetadataParser.parseTransactionMetadata;
import static org.neo4j.bolt.v3.messaging.request.MessageMetadataParser.parseTransactionTimeout;

public class BeginMessage implements RequestMessage
{
    public static final byte SIGNATURE = 0x11;

    private final MapValue meta;
    private final Bookmark bookmark;
    private final Duration txTimeout;
    private final Map<String,Object> txMetadata;

    public BeginMessage() throws BoltIOException
    {
        this( VirtualValues.EMPTY_MAP );
    }

    public BeginMessage( MapValue meta ) throws BoltIOException
    {
        this.meta = requireNonNull( meta );
        this.bookmark = Bookmark.fromParamsOrNull( meta );
        this.txTimeout = parseTransactionTimeout( meta );
        this.txMetadata = parseTransactionMetadata( meta );
    }

    public Bookmark bookmark()
    {
        return this.bookmark;
    }

    public Duration transactionTimeout()
    {
        return this.txTimeout;
    }

    @Override
    public boolean safeToProcessInAnyState()
    {
        return false;
    }

    @Override
    public boolean equals( Object o )
    {
        if ( this == o )
        {
            return true;
        }
        if ( o == null || getClass() != o.getClass() )
        {
            return false;
        }
        BeginMessage that = (BeginMessage) o;
        return Objects.equals( meta, that.meta );
    }

    @Override
    public int hashCode()
    {
        return Objects.hash( meta );
    }

    @Override
    public String toString()
    {
        return "BEGIN " + meta;
    }

    public MapValue meta()
    {
        return meta;
    }

    public Map<String,Object> transactionMetadata()
    {
        return txMetadata;
    }
}
