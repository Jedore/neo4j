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
package org.neo4j.packstream.error.struct;

import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.packstream.error.reader.PackstreamReaderException;

public class PackstreamStructException extends PackstreamReaderException implements Status.HasStatus {

    public PackstreamStructException() {}

    public PackstreamStructException(String message) {
        super(message);
    }

    public PackstreamStructException(String message, Throwable cause) {
        super(message, cause);
    }

    public PackstreamStructException(Throwable cause) {
        super(cause);
    }

    @Override
    public Status status() {
        return Status.Request.InvalidFormat;
    }
}
