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
package io.aeron.protocol;

import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;

import static java.nio.ByteOrder.LITTLE_ENDIAN;

/**
 * Flyweight for a Status Message Packet
 *
 * @see <a href="https://github.com/real-logic/Aeron/wiki/Protocol-Specification#status-messages">Status Message</a>
 */
public class StatusMessageFlyweight extends HeaderFlyweight
{
    /**
     * Length of the Status Message Packet
     */
    public static final int HEADER_LENGTH = 36;

    /**
     * Publisher should send SETUP frame
     */
    public static final short SEND_SETUP_FLAG = 0x80;

    private static final int SESSION_ID_FIELD_OFFSET = 8;
    private static final int STREAM_ID_FIELD_OFFSET = 12;
    private static final int CONSUMPTION_TERM_ID_FIELD_OFFSET = 16;
    private static final int CONSUMPTION_TERM_OFFSET_FIELD_OFFSET = 20;
    private static final int RECEIVER_WINDOW_FIELD_OFFSET = 24;
    private static final int RECEIVER_ID_FIELD_OFFSET = 28;
    private static final int APP_SPECIFIC_FEEDBACK_FIELD_OFFSET = 36;

    public StatusMessageFlyweight()
    {
    }

    public StatusMessageFlyweight(final ByteBuffer buffer)
    {
        super(buffer);
    }

    public StatusMessageFlyweight(final UnsafeBuffer buffer)
    {
        super(buffer);
    }

    /**
     * return session id field
     *
     * @return session id field
     */
    public int sessionId()
    {
        return getInt(SESSION_ID_FIELD_OFFSET, LITTLE_ENDIAN);
    }

    /**
     * set session id field
     *
     * @param sessionId field value
     * @return flyweight
     */
    public StatusMessageFlyweight sessionId(final int sessionId)
    {
        putInt(SESSION_ID_FIELD_OFFSET, sessionId, LITTLE_ENDIAN);

        return this;
    }

    /**
     * return stream id field
     *
     * @return stream id field
     */
    public int streamId()
    {
        return getInt(STREAM_ID_FIELD_OFFSET, LITTLE_ENDIAN);
    }

    /**
     * set stream id field
     *
     * @param streamId field value
     * @return flyweight
     */
    public StatusMessageFlyweight streamId(final int streamId)
    {
        putInt(STREAM_ID_FIELD_OFFSET, streamId, LITTLE_ENDIAN);

        return this;
    }

    /**
     * return highest consumption term offset field
     *
     * @return highest consumption term offset field
     */
    public int consumptionTermOffset()
    {
        return getInt(CONSUMPTION_TERM_OFFSET_FIELD_OFFSET, LITTLE_ENDIAN);
    }

    /**
     * set highest consumption term offset field
     *
     * @param termOffset field value
     * @return flyweight
     */
    public StatusMessageFlyweight consumptionTermOffset(final int termOffset)
    {
        putInt(CONSUMPTION_TERM_OFFSET_FIELD_OFFSET, termOffset, LITTLE_ENDIAN);

        return this;
    }

    /**
     * return highest consumption term id field
     *
     * @return highest consumption term id field
     */
    public int consumptionTermId()
    {
        return getInt(CONSUMPTION_TERM_ID_FIELD_OFFSET, LITTLE_ENDIAN);
    }

    /**
     * set highest consumption term id field
     *
     * @param termId field value
     * @return flyweight
     */
    public StatusMessageFlyweight consumptionTermId(final int termId)
    {
        putInt(CONSUMPTION_TERM_ID_FIELD_OFFSET, termId, LITTLE_ENDIAN);

        return this;
    }

    /**
     * return receiver window field
     *
     * @return receiver window field
     */
    public int receiverWindowLength()
    {
        return getInt(RECEIVER_WINDOW_FIELD_OFFSET, LITTLE_ENDIAN);
    }

    /**
     * set receiver window field
     *
     * @param receiverWindowLength field value
     * @return flyweight
     */
    public StatusMessageFlyweight receiverWindowLength(final int receiverWindowLength)
    {
        putInt(RECEIVER_WINDOW_FIELD_OFFSET, receiverWindowLength, LITTLE_ENDIAN);

        return this;
    }

    public long receiverId()
    {
        return getLong(RECEIVER_ID_FIELD_OFFSET, LITTLE_ENDIAN);
    }

    public StatusMessageFlyweight receiverId(final long id)
    {
        putLong(RECEIVER_ID_FIELD_OFFSET, id, LITTLE_ENDIAN);

        return this;
    }

    /**
     * Retrieve the Application Specific Feedback (if present) from the Status Message.
     *
     * @param destination to store the feedback
     * @return the number of bytes in the feedback copied into the destination
     */
    public int applicationSpecificFeedback(final byte[] destination)
    {
        final int frameLength = frameLength();
        int result = 0;

        if (frameLength > HEADER_LENGTH)
        {
            if (frameLength > capacity())
            {
                throw new IllegalStateException(String.format(
                    "SM application specific feedback (%d) is truncated (%d)",
                    frameLength - HEADER_LENGTH,
                    capacity() - HEADER_LENGTH));
            }

            final int copyLength = Math.min(destination.length, frameLength - HEADER_LENGTH);
            getBytes(APP_SPECIFIC_FEEDBACK_FIELD_OFFSET, destination, 0, copyLength);
            result = copyLength;
        }

        return result;
    }

    /**
     * Set the Application Specific Feedback for the Status Message.
     *
     * @param source of the feedback to set
     * @param offset of the feedback in the source
     * @param length of the feedback in bytes
     * @return flyweight
     */
    public StatusMessageFlyweight applicationSpecificFeedback(final byte[] source, final int offset, final int length)
    {
        frameLength(HEADER_LENGTH + length);
        putBytes(APP_SPECIFIC_FEEDBACK_FIELD_OFFSET, source, offset, length);

        return this;
    }

    public String toString()
    {
        final StringBuilder sb = new StringBuilder();
        final String formattedFlags = String.format("%1$8s", Integer.toBinaryString(flags())).replace(' ', '0');

        sb.append("STATUS Message{")
            .append("frame_length=").append(frameLength())
            .append(" version=").append(version())
            .append(" flags=").append(formattedFlags)
            .append(" type=").append(headerType())
            .append(" session_id=").append(sessionId())
            .append(" stream_id=").append(streamId())
            .append(" consumption_term_id=").append(consumptionTermId())
            .append(" consumption_term_offset=").append(consumptionTermOffset())
            .append(" receiver_window_length=").append(receiverWindowLength())
            .append("}");

        return sb.toString();
    }
}
