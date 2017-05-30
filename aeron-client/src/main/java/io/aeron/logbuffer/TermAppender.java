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
package io.aeron.logbuffer;

import io.aeron.ReservedValueSupplier;
import org.agrona.DirectBuffer;
import org.agrona.UnsafeAccess;
import org.agrona.concurrent.UnsafeBuffer;

import static io.aeron.logbuffer.FrameDescriptor.BEGIN_FRAG_FLAG;
import static io.aeron.logbuffer.FrameDescriptor.END_FRAG_FLAG;
import static io.aeron.logbuffer.FrameDescriptor.FRAME_ALIGNMENT;
import static io.aeron.logbuffer.FrameDescriptor.PADDING_FRAME_TYPE;
import static io.aeron.logbuffer.FrameDescriptor.frameFlags;
import static io.aeron.logbuffer.FrameDescriptor.frameLengthOrdered;
import static io.aeron.logbuffer.FrameDescriptor.frameType;
import static io.aeron.logbuffer.LogBufferDescriptor.TERM_TAIL_COUNTERS_OFFSET;
import static io.aeron.protocol.DataHeaderFlyweight.*;
import static org.agrona.BitUtil.SIZE_OF_LONG;
import static org.agrona.BitUtil.align;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

/**
 * Term buffer appender which supports many producers concurrently writing an append-only log.
 * <p>
 * <b>Note:</b> This class is threadsafe.
 * <p>
 * Messages are appended to a term using a framing protocol as described in {@link FrameDescriptor}.
 * <p>
 * A default message header is applied to each message with the fields filled in for fragment flags, type, term number,
 * as appropriate.
 * <p>
 * A message of type {@link FrameDescriptor#PADDING_FRAME_TYPE} is appended at the end of the buffer if claimed
 * space is not sufficiently large to accommodate the message about to be written.
 */
public class TermAppender
{
    /**
     * The append operation tripped the end of the buffer and needs to rotate.
     */
    public static final int TRIPPED = -1;

    /**
     * The append operation went past the end of the buffer and failed.
     */
    public static final int FAILED = -2;

    private final long tailAddressOffset;
    private final byte[] tailBuffer;
    private final UnsafeBuffer termBuffer;

    /**
     * Construct a view over a term buffer and state buffer for appending frames.
     *
     * @param termBuffer     for where messages are stored.
     * @param metaDataBuffer for where the state of writers is stored manage concurrency.
     * @param partitionIndex for this will be the active appender.
     */
    public TermAppender(final UnsafeBuffer termBuffer, final UnsafeBuffer metaDataBuffer, final int partitionIndex)
    {
        final int tailCounterOffset = TERM_TAIL_COUNTERS_OFFSET + (partitionIndex * SIZE_OF_LONG);
        metaDataBuffer.boundsCheck(tailCounterOffset, SIZE_OF_LONG);

        this.termBuffer = termBuffer;
        tailBuffer = metaDataBuffer.byteArray();
        tailAddressOffset = metaDataBuffer.addressOffset() + tailCounterOffset;
    }

    /**
     * Get the raw current tail value in a volatile memory ordering fashion.
     *
     * @return the current tail value.
     */
    public long rawTailVolatile()
    {
        return UnsafeAccess.UNSAFE.getLongVolatile(tailBuffer, tailAddressOffset);
    }

    /**
     * Set the value for the tail counter.
     *
     * @param termId for the tail counter
     */
    public void tailTermId(final int termId)
    {
        UnsafeAccess.UNSAFE.putLong(tailBuffer, tailAddressOffset, ((long)termId) << 32);
    }

    /**
     * Claim length of a the term buffer for writing in the message with zero copy semantics.
     *
     * @param header      for writing the default header.
     * @param length      of the message to be written.
     * @param bufferClaim to be updated with the claimed region.
     * @return the resulting offset of the term after the append on success otherwise {@link #TRIPPED}
     * or {@link #FAILED} packed with the termId if a padding record was inserted at the end.
     */
    public long claim(final HeaderWriter header, final int length, final BufferClaim bufferClaim)
    {
        final int frameLength = length + HEADER_LENGTH;
        final int alignedLength = align(frameLength, FRAME_ALIGNMENT);
        final long rawTail = getAndAddRawTail(alignedLength);
        final long termOffset = rawTail & 0xFFFF_FFFFL;

        final UnsafeBuffer termBuffer = this.termBuffer;
        final int termLength = termBuffer.capacity();

        long resultingOffset = termOffset + alignedLength;
        if (resultingOffset > termLength)
        {
            resultingOffset = handleEndOfLogCondition(termBuffer, termOffset, header, termLength, termId(rawTail));
        }
        else
        {
            final int offset = (int)termOffset;
            header.write(termBuffer, offset, frameLength, termId(rawTail));
            bufferClaim.wrap(termBuffer, offset, frameLength);
        }

        return resultingOffset;
    }

    /**
     * Append an unfragmented message to the the term buffer.
     *
     * @param header                for writing the default header.
     * @param srcBuffer             containing the message.
     * @param srcOffset             at which the message begins.
     * @param length                of the message in the source buffer.
     * @param reservedValueSupplier {@link ReservedValueSupplier} for the frame.
     * @return the resulting offset of the term after the append on success otherwise {@link #TRIPPED} or
     * {@link #FAILED} packed with the termId if a padding record was inserted at the end.
     */
    public long appendUnfragmentedMessage(
        final HeaderWriter header,
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int length,
        final ReservedValueSupplier reservedValueSupplier)
    {
        final int frameLength = length + HEADER_LENGTH;
        final int alignedLength = align(frameLength, FRAME_ALIGNMENT);
        final long rawTail = getAndAddRawTail(alignedLength);
        final long termOffset = rawTail & 0xFFFF_FFFFL;

        final UnsafeBuffer termBuffer = this.termBuffer;
        final int termLength = termBuffer.capacity();

        long resultingOffset = termOffset + alignedLength;
        if (resultingOffset > termLength)
        {
            resultingOffset = handleEndOfLogCondition(termBuffer, termOffset, header, termLength, termId(rawTail));
        }
        else
        {
            final int offset = (int)termOffset;
            header.write(termBuffer, offset, frameLength, termId(rawTail));
            termBuffer.putBytes(offset + HEADER_LENGTH, srcBuffer, srcOffset, length);

            if (null != reservedValueSupplier)
            {
                final long reservedValue = reservedValueSupplier.get(termBuffer, offset, frameLength);
                termBuffer.putLong(offset + RESERVED_VALUE_OFFSET, reservedValue, LITTLE_ENDIAN);
            }

            frameLengthOrdered(termBuffer, offset, frameLength);
        }

        return resultingOffset;
    }

    /**
     * Append a fragmented message to the the term buffer.
     * The message will be split up into fragments of MTU length minus header.
     *
     * @param header                for writing the default header.
     * @param srcBuffer             containing the message.
     * @param srcOffset             at which the message begins.
     * @param length                of the message in the source buffer.
     * @param maxPayloadLength      that the message will be fragmented into.
     * @param reservedValueSupplier {@link ReservedValueSupplier} for the frame.
     * @return the resulting offset of the term after the append on success otherwise {@link #TRIPPED}
     * or {@link #FAILED} packed with the termId if a padding record was inserted at the end.
     */
    public long appendFragmentedMessage(
        final HeaderWriter header,
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int length,
        final int maxPayloadLength,
        final ReservedValueSupplier reservedValueSupplier)
    {
        final int numMaxPayloads = length / maxPayloadLength;
        final int remainingPayload = length % maxPayloadLength;
        final int lastFrameLength = remainingPayload > 0 ? align(remainingPayload + HEADER_LENGTH, FRAME_ALIGNMENT) : 0;
        final int requiredLength = (numMaxPayloads * (maxPayloadLength + HEADER_LENGTH)) + lastFrameLength;
        final long rawTail = getAndAddRawTail(requiredLength);
        final int termId = termId(rawTail);
        final long termOffset = rawTail & 0xFFFF_FFFFL;

        final UnsafeBuffer termBuffer = this.termBuffer;
        final int termLength = termBuffer.capacity();

        long resultingOffset = termOffset + requiredLength;
        if (resultingOffset > termLength)
        {
            resultingOffset = handleEndOfLogCondition(termBuffer, termOffset, header, termLength, termId);
        }
        else
        {
            int offset = (int)termOffset;
            byte flags = BEGIN_FRAG_FLAG;
            int remaining = length;
            do
            {
                final int bytesToWrite = Math.min(remaining, maxPayloadLength);
                final int frameLength = bytesToWrite + HEADER_LENGTH;
                final int alignedLength = align(frameLength, FRAME_ALIGNMENT);

                header.write(termBuffer, offset, frameLength, termId);
                termBuffer.putBytes(
                    offset + HEADER_LENGTH,
                    srcBuffer,
                    srcOffset + (length - remaining),
                    bytesToWrite);

                if (remaining <= maxPayloadLength)
                {
                    flags |= END_FRAG_FLAG;
                }

                frameFlags(termBuffer, offset, flags);

                if (null != reservedValueSupplier)
                {
                    final long reservedValue = reservedValueSupplier.get(termBuffer, offset, frameLength);
                    termBuffer.putLong(offset + RESERVED_VALUE_OFFSET, reservedValue, LITTLE_ENDIAN);
                }

                frameLengthOrdered(termBuffer, offset, frameLength);

                flags = 0;
                offset += alignedLength;
                remaining -= bytesToWrite;
            }
            while (remaining > 0);
        }

        return resultingOffset;
    }


    /**
     * Pack the values for termOffset and termId into a long for returning on the stack.
     *
     * @param termId     value to be packed.
     * @param termOffset value to be packed.
     * @return a long with both ints packed into it.
     */
    public static long pack(final int termId, final int termOffset)
    {
        return ((long)termId << 32) | (termOffset & 0xFFFF_FFFFL);
    }

    /**
     * The termOffset as a result of the append
     *
     * @param result into which the termOffset value has been packed.
     * @return the termOffset after the append
     */
    public static int termOffset(final long result)
    {
        return (int)result;
    }

    /**
     * The termId in which the append operation took place.
     *
     * @param result into which the termId value has been packed.
     * @return the termId in which the append operation took place.
     */
    public static int termId(final long result)
    {
        return (int)(result >>> 32);
    }

    private long handleEndOfLogCondition(
        final UnsafeBuffer termBuffer,
        final long termOffset,
        final HeaderWriter header,
        final int termLength,
        final int termId)
    {
        int resultingOffset = FAILED;

        if (termOffset <= termLength)
        {
            resultingOffset = TRIPPED;

            if (termOffset < termLength)
            {
                final int offset = (int)termOffset;
                final int paddingLength = termLength - offset;
                header.write(termBuffer, offset, paddingLength, termId);
                frameType(termBuffer, offset, PADDING_FRAME_TYPE);
                frameLengthOrdered(termBuffer, offset, paddingLength);
            }
        }

        return pack(termId, resultingOffset);
    }

    private long getAndAddRawTail(final int alignedLength)
    {
        return UnsafeAccess.UNSAFE.getAndAddLong(tailBuffer, tailAddressOffset, alignedLength);
    }
}
