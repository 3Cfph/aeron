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
package io.aeron.status;

import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.status.CountersManager;
import org.agrona.concurrent.status.CountersReader;

import static org.agrona.BitUtil.SIZE_OF_INT;

/**
 * Allocates {@link AtomicCounter} indicating channel endpoint status.
 */
public class ChannelEndpointStatus
{
    /**
     * Channel is being initialized.
     */
    public static final long INITIALIZING = 0;

    /**
     * Channel has errored. Check error log for information.
     */
    public static final long ERRORED = -1;

    /**
     * Channel has finished initialization successfully and is active.
     */
    public static final long ACTIVE = 1;

    /**
     * Channel is being closed.
     */
    public static final long CLOSING = 2;

    /**
     * No counter ID is allocated.
     */
    public static final int NO_ID_ALLOCATED = -1;

    /**
     * Offset in the key meta data for the channel of the counter.
     */
    public static final int CHANNEL_OFFSET = 0;

    /**
     * String representation of the channel status.
     *
     * @param status to be converted.
     * @return representation of the channel status.
     */
    public static String status(final long status)
    {
        if (INITIALIZING == status)
        {
            return "INITIALIZING";
        }
        if (ERRORED == status)
        {
            return "ERRORED";
        }
        if (ACTIVE == status)
        {
            return "ACTIVE";
        }
        if (CLOSING == status)
        {
            return "CLOSING";
        }

        return "unknown id=" + status;
    }

    /**
     * The maximum length in bytes of the encoded channel identity.
     */
    public static final int MAX_CHANNEL_LENGTH = CountersReader.MAX_KEY_LENGTH - (CHANNEL_OFFSET + SIZE_OF_INT);

    /**
     * Allocate an indicator for tracking the status of a channel endpoint.
     *
     * @param name            of the counter for the label.
     * @param typeId          of the counter for classification.
     * @param countersManager from which to allocated the underlying storage.
     * @param channel         for the stream of messages.
     * @return a new {@link AtomicCounter} for tracking the status.
     */
    public static AtomicCounter allocate(
        final String name,
        final int typeId,
        final CountersManager countersManager,
        final String channel)
    {
        final String label = name + ": " + channel;

        return countersManager.newCounter(
            label,
            typeId,
            (buffer) -> buffer.putStringAscii(
                CHANNEL_OFFSET,
                channel.length() > MAX_CHANNEL_LENGTH ? channel.substring(0, MAX_CHANNEL_LENGTH) : channel));
    }
}
