/*
 * Copyright 2014-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.archiver;

import io.aeron.ExclusivePublication;
import io.aeron.archiver.codecs.*;
import io.aeron.logbuffer.ExclusiveBufferClaim;
import org.agrona.*;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;

import static io.aeron.archiver.Catalog.CATALOG_FRAME_LENGTH;
import static org.agrona.BitUtil.CACHE_LINE_LENGTH;

class ListRecordingsSession implements ArchiveConductor.Session
{
    private static final int HEADER_LENGTH = MessageHeaderEncoder.ENCODED_LENGTH;
    private static final long NOT_FOUND_HEADER;
    private static final long DESCRIPTOR_HEADER;

    static
    {
        // TODO: How will this cope on a Big Endian machine?

        // create constant header values to avoid recalculation on each message sent
        final MessageHeaderEncoder encoder = new MessageHeaderEncoder();
        encoder.wrap(new UnsafeBuffer(new byte[HEADER_LENGTH]), 0);
        encoder.schemaId(RecordingNotFoundResponseEncoder.SCHEMA_ID);
        encoder.version(RecordingNotFoundResponseEncoder.SCHEMA_VERSION);
        encoder.blockLength(RecordingNotFoundResponseEncoder.BLOCK_LENGTH);
        encoder.templateId(RecordingNotFoundResponseEncoder.TEMPLATE_ID);
        NOT_FOUND_HEADER = encoder.buffer().getLong(0);

        encoder.schemaId(RecordingDescriptorEncoder.SCHEMA_ID);
        encoder.version(RecordingDescriptorEncoder.SCHEMA_VERSION);
        encoder.blockLength(RecordingDescriptorEncoder.BLOCK_LENGTH);
        encoder.templateId(RecordingDescriptorEncoder.TEMPLATE_ID);
        DESCRIPTOR_HEADER = encoder.buffer().getLong(0);
    }

    private enum State
    {
        INIT,
        SENDING,
        CLOSE,
        DONE
    }

    private final ByteBuffer byteBuffer = BufferUtil.allocateDirectAligned(Catalog.RECORD_LENGTH, CACHE_LINE_LENGTH);
    private final UnsafeBuffer unsafeBuffer = new UnsafeBuffer(byteBuffer);
    private final ExclusiveBufferClaim bufferClaim = new ExclusiveBufferClaim();

    private final ExclusivePublication reply;
    private final int fromId;
    private final int toId;
    private final Catalog index;
    private final ClientSessionProxy proxy;
    private final int correlationId;

    private int recordingId;
    private State state = State.INIT;

    ListRecordingsSession(
        final int correlationId, final ExclusivePublication reply,
        final int fromId,
        final int toId,
        final Catalog index,
        final ClientSessionProxy proxy)
    {
        this.reply = reply;
        recordingId = fromId;
        this.fromId = fromId;
        this.toId = toId;
        this.index = index;
        this.proxy = proxy;
        this.correlationId = correlationId;
    }

    public void abort()
    {
        state = State.CLOSE;
    }

    public boolean isDone()
    {
        return state == State.DONE;
    }

    public void remove(final ArchiveConductor conductor)
    {
    }

    public int doWork()
    {
        int workDone = 0;

        switch (state)
        {
            case INIT:
                workDone += init();
                break;

            case SENDING:
                workDone += sendDescriptors();
                break;

            case CLOSE:
                workDone += close();
                break;

        }

        return workDone;
    }

    private int close()
    {
        state = State.DONE;
        return 1;
    }

    private int sendDescriptors()
    {
        final int limit = Math.min(recordingId + 4, toId);
        for (; recordingId <= limit; recordingId++)
        {
            final RecordingSession session = index.getRecordingSession(recordingId);
            if (session == null)
            {
                byteBuffer.clear();
                unsafeBuffer.wrap(byteBuffer);
                try
                {
                    if (!index.readDescriptor(recordingId, byteBuffer))
                    {
                        // return relevant error
                        if (reply.tryClaim(
                            HEADER_LENGTH + RecordingNotFoundResponseDecoder.BLOCK_LENGTH, bufferClaim) > 0L)
                        {
                            final MutableDirectBuffer buffer = bufferClaim.buffer();
                            final int offset = bufferClaim.offset();
                            buffer.putLong(offset, NOT_FOUND_HEADER);
                            buffer.putInt(offset + 8, recordingId);
                            buffer.putInt(offset + 12, index.maxRecordingId());
                            buffer.putInt(offset + 16, correlationId);
                            bufferClaim.commit();
                            state = State.CLOSE;
                        }

                        return 0;
                    }
                }
                catch (final IOException ex)
                {
                    state = State.CLOSE;
                    LangUtil.rethrowUnchecked(ex);
                }
            }
            else
            {
                unsafeBuffer.wrap(session.metaDataBuffer());
            }

            final int length = unsafeBuffer.getInt(0);
            unsafeBuffer.putLong(CATALOG_FRAME_LENGTH - HEADER_LENGTH, DESCRIPTOR_HEADER);
            unsafeBuffer.putInt(
                CATALOG_FRAME_LENGTH + RecordingDescriptorDecoder.correlationIdEncodingOffset(), correlationId);

            reply.offer(unsafeBuffer, CATALOG_FRAME_LENGTH - HEADER_LENGTH, length + HEADER_LENGTH);
        }

        if (recordingId > toId)
        {
            state = State.CLOSE;
        }

        return 1;
    }

    private int init()
    {
        if (fromId > toId)
        {
            proxy.sendResponse(reply, "Requested range is reversed (to < from)", correlationId);
            state = State.CLOSE;
        }
        else if (toId > index.maxRecordingId())
        {
            proxy.sendResponse(reply, "Requested range exceeds available range (to > max)", correlationId);
            state = State.CLOSE;
        }
        else
        {
            state = State.SENDING;
        }

        return 1;
    }
}
