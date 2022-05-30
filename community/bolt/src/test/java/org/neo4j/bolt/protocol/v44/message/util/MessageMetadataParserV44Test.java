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
package org.neo4j.bolt.protocol.v44.message.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.neo4j.packstream.error.reader.PackstreamReaderException;
import org.neo4j.packstream.error.struct.IllegalStructArgumentException;
import org.neo4j.values.storable.Values;
import org.neo4j.values.virtual.MapValue;
import org.neo4j.values.virtual.MapValueBuilder;

class MessageMetadataParserV44Test {

    @Test
    void shouldParseImpersonatedUser() throws PackstreamReaderException {
        var meta = new MapValueBuilder();
        meta.add("imp_user", Values.stringValue("bob"));

        var impersonatedUser = MessageMetadataParserV44.parseImpersonatedUser(meta.build());

        assertThat(impersonatedUser).isEqualTo("bob");
    }

    @Test
    void shouldHandleMissingImpersonatedUserField() throws PackstreamReaderException {
        var impersonatedUser = MessageMetadataParserV44.parseImpersonatedUser(MapValue.EMPTY);

        assertThat(impersonatedUser).isNull();
    }

    @Test
    void shouldFailWithIllegalStructArgumentWhenInvalidImpersonatedUserIsPassed() {
        var meta = new MapValueBuilder();
        meta.add("imp_user", Values.longValue(42));

        assertThatExceptionOfType(IllegalStructArgumentException.class)
                .isThrownBy(() -> MessageMetadataParserV44.parseImpersonatedUser(meta.build()))
                .withMessage(
                        "Illegal value for field \"imp_user\": Expecting impersonated user value to be a String value, but got: Long(42)");
    }
}
