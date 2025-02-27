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
package org.neo4j.kernel.impl.transaction;

import static org.neo4j.storageengine.api.TransactionIdStore.BASE_CHUNK_NUMBER;
import static org.neo4j.storageengine.api.TransactionIdStore.UNKNOWN_CONSENSUS_INDEX;

import java.io.IOException;
import java.util.List;
import org.neo4j.common.Subject;
import org.neo4j.io.fs.WritableChecksumChannel;
import org.neo4j.kernel.impl.api.chunk.ChunkMetadata;
import org.neo4j.kernel.impl.api.chunk.CommandChunk;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntry;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryCommit;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryStart;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryWriter;
import org.neo4j.kernel.impl.transaction.log.entry.v56.LogEntryChunkEnd;
import org.neo4j.kernel.impl.transaction.log.entry.v56.LogEntryChunkStart;
import org.neo4j.storageengine.api.CommandBatch;
import org.neo4j.storageengine.api.StorageCommand;

public record CommittedChunkRepresentation(
        LogEntryChunkStart chunkStart, CommandBatch commandBatch, LogEntryChunkEnd chunkEnd)
        implements CommittedCommandBatch {

    public static CommittedChunkRepresentation createChunkRepresentation(
            LogEntry start, List<StorageCommand> commands, LogEntry end) {
        LogEntryChunkStart logEntryChunkStart = createChunkStart(start);
        LogEntryChunkEnd logEntryChunkEnd = createChunkEnd(end, logEntryChunkStart);
        ChunkMetadata chunkMetadata = new ChunkMetadata(
                start instanceof LogEntryStart,
                end instanceof LogEntryCommit,
                false,
                logEntryChunkStart.getPreviousBatchLogPosition(),
                logEntryChunkStart.getChunkId(),
                UNKNOWN_CONSENSUS_INDEX,
                logEntryChunkStart.getTimeWritten(),
                -1,
                logEntryChunkStart.getTimeWritten(),
                -1,
                logEntryChunkStart.kernelVersion(),
                Subject.AUTH_DISABLED);
        return new CommittedChunkRepresentation(
                logEntryChunkStart, new CommandChunk(commands, chunkMetadata), logEntryChunkEnd);
    }

    @Override
    public int serialize(LogEntryWriter<? extends WritableChecksumChannel> writer) throws IOException {
        byte version = chunkStart.kernelVersion().version();
        writer.writeChunkStartEntry(
                version,
                chunkStart.getTimeWritten(),
                chunkStart.getChunkId(),
                chunkStart.getPreviousBatchLogPosition());
        writer.serialize(commandBatch);
        return writer.writeChunkEndEntry(version, chunkEnd.getTransactionId(), chunkEnd.getChunkId());
    }

    @Override
    public int checksum() {
        return chunkEnd.getChecksum();
    }

    @Override
    public long timeWritten() {
        return chunkStart.getTimeWritten();
    }

    @Override
    public long txId() {
        return chunkEnd.getTransactionId();
    }

    @Override
    public boolean isRollback() {
        return false;
    }

    @Override
    public LogPosition previousBatchLogPosition() {
        return chunkStart.getPreviousBatchLogPosition();
    }

    private static LogEntryChunkStart createChunkStart(LogEntry start) {
        if (start instanceof LogEntryChunkStart chunkStart) {
            return chunkStart;
        } else if (start instanceof LogEntryStart entryStart) {
            return new LogEntryChunkStart(
                    entryStart.kernelVersion(),
                    entryStart.getTimeWritten(),
                    BASE_CHUNK_NUMBER,
                    LogPosition.UNSPECIFIED);
        } else {
            throw new IllegalArgumentException("Was expecting start record. Actual entry: " + start);
        }
    }

    private static LogEntryChunkEnd createChunkEnd(LogEntry end, LogEntryChunkStart chunkStart) {
        if (end instanceof LogEntryChunkEnd endChunk) {
            return endChunk;
        } else if (end instanceof LogEntryCommit commit) {
            return new LogEntryChunkEnd(commit.getTxId(), chunkStart.getChunkId(), commit.getChecksum());
        } else {
            throw new IllegalArgumentException("Was expecting end record. Actual entry: " + end);
        }
    }
}
