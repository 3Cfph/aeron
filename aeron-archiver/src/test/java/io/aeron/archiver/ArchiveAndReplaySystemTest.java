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

import io.aeron.*;
import io.aeron.archiver.client.*;
import io.aeron.archiver.codecs.RecordingDescriptorDecoder;
import io.aeron.driver.*;
import io.aeron.logbuffer.*;
import io.aeron.protocol.*;
import org.agrona.*;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.*;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import java.io.*;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

import static io.aeron.archiver.ArchiveUtil.*;
import static io.aeron.archiver.TestUtil.*;
import static io.aeron.logbuffer.FrameDescriptor.FRAME_ALIGNMENT;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.*;

public class ArchiveAndReplaySystemTest
{
    public static class FailRecordingEventsListener implements RecordingEventsListener
    {
        public void onProgress(
            final int recordingId,
            final int initialTermId,
            final int initialTermOffset,
            final int termId,
            final int termOffset)
        {
            fail();
        }

        public void onStart(
            final int recordingId,
            final String source,
            final int sessionId,
            final String channel,
            final int streamId)
        {
            fail();
        }

        public void onStop(final int recordingId)
        {
            fail();
        }
    }

    public static class FailResponseListener implements ResponseListener
    {
        public void onResponse(final String err, final long correlationId)
        {
            fail();
        }

        public void onReplayStarted(final int replayId, final long correlationId)
        {
            fail();
        }

        public void onReplayAborted(final int lastTermId, final int lastTermOffset, final long correlationId)
        {
            fail();
        }

        public void onRecordingDescriptor(
            final int recordingId,
            final int segmentFileLength,
            final int termBufferLength,
            final long startTime,
            final int initialTermId,
            final int initialTermOffset,
            final long endTime,
            final int lastTermId,
            final int lastTermOffset,
            final String source,
            final int sessionId,
            final String channel,
            final int streamId,
            final long correlationId)
        {
            fail();
        }

        public void onRecordingNotFound(final int recordingId, final int maxRecordingId, final long correlationId)
        {
            fail();
        }
    }

    private static final double MEGABYTE = 1024.0d * 1024.0d;

    private static final String REPLY_URI = "aeron:udp?endpoint=127.0.0.1:54327";
    private static final int REPLY_STREAM_ID = 100;
    private static final String REPLAY_URI = "aeron:ipc?endpoint=127.0.0.1:54326";
    private static final String PUBLISH_URI = "aeron:udp?endpoint=127.0.0.1:54325";
    private static final int PUBLISH_STREAM_ID = 1;
    private static final int MAX_FRAGMENT_SIZE = 1024;
    private final MediaDriver.Context driverCtx = new MediaDriver.Context();
    private final Archiver.Context archiverCtx = new Archiver.Context();
    private Aeron publishingClient;
    private Archiver archiver;
    private MediaDriver driver;
    private UnsafeBuffer buffer = new UnsafeBuffer(new byte[4096]);
    private File archiveDir;
    private int recordingId;
    private String source;
    private long remaining;
    private int nextFragmentOffset;
    private int fragmentCount;
    private int[] fragmentLength;
    private long totalDataLength;
    private long totalRecordingLength;
    private long recorded;
    private volatile int lastTermId = -1;
    private Throwable trackerError;
    private Random rnd = new Random();
    private long seed;

    @Rule
    public TestWatcher testWatcher = new TestWatcher()
    {
        protected void failed(final Throwable t, final Description description)
        {
            System.err.println(
                "ArchiveAndReplaySystemTest failed with random seed: " + ArchiveAndReplaySystemTest.this.seed);
        }
    };
    private Subscription reply;
    private long correlationId;

    @Before
    public void setUp() throws Exception
    {
        seed = System.nanoTime();
        rnd.setSeed(seed);

        driverCtx
            .termBufferSparseFile(true)
            .threadingMode(ThreadingMode.SHARED)
            .errorHandler(LangUtil::rethrowUnchecked)
            .dirsDeleteOnStart(true);

        driver = MediaDriver.launch(driverCtx);
        archiveDir = TestUtil.makeTempDir();
        archiverCtx.archiveDir(archiveDir);
        archiver = Archiver.launch(archiverCtx);
        println("Archiver started, dir: " + archiverCtx.archiveDir().getAbsolutePath());
        publishingClient = Aeron.connect();
    }

    @After
    public void closeEverything() throws Exception
    {
        CloseHelper.close(publishingClient);
        CloseHelper.close(archiver);
        CloseHelper.close(driver);

        if (null != archiveDir)
        {
            IoUtil.delete(archiveDir, false);
        }

        driverCtx.deleteAeronDirectory();
    }

    @Test(timeout = 60000)
    public void recordAndReplay() throws IOException, InterruptedException
    {
        try (Publication controlPublication = publishingClient.addPublication(
            archiverCtx.controlRequestChannel(), archiverCtx.controlRequestStreamId());
             Subscription recordingEvents = publishingClient.addSubscription(
                 archiverCtx.recordingEventsChannel(), archiverCtx.recordingEventsStreamId()))
        {
            final ArchiveClient client = new ArchiveClient(controlPublication, recordingEvents);

            TestUtil.awaitPublicationIsConnected(controlPublication);
            TestUtil.awaitSubscriptionIsConnected(recordingEvents);
            println("Archive service connected");

            reply = publishingClient.addSubscription(REPLY_URI, REPLY_STREAM_ID);
            client.connect(REPLY_URI, REPLY_STREAM_ID);
            TestUtil.awaitSubscriptionIsConnected(reply);
            println("Client connected");

            verifyEmptyDescriptorList(client);
            final long startRecordingCorrelationId = this.correlationId++;
            waitFor(() -> client.startRecording(PUBLISH_URI, PUBLISH_STREAM_ID, startRecordingCorrelationId));
            println("Recording requested");
            waitForOk(client, reply, startRecordingCorrelationId);

            final Publication publication = publishingClient.addPublication(PUBLISH_URI, PUBLISH_STREAM_ID);
            TestUtil.awaitPublicationIsConnected(publication);

            waitFor(() -> client.pollEvents(new FailRecordingEventsListener()
            {
                public void onStart(
                    final int recordingId0,
                    final String iSource,
                    final int sessionId,
                    final String channel,
                    final int streamId)
                {
                    recordingId = recordingId0;
                    assertThat(streamId, is(PUBLISH_STREAM_ID));
                    assertThat(sessionId, is(publication.sessionId()));

                    source = iSource;
                    assertThat(channel, is(PUBLISH_URI));
                    println("Recording started. source: " + source);
                }
            }, 1) != 0);

            verifyDescriptorListOngoingArchive(client, publication, 0);
            final int messageCount = prepAndSendMessages(client, publication);
            verifyDescriptorListOngoingArchive(client, publication, totalRecordingLength);

            assertNull(trackerError);
            println("All data arrived");

            println("Request stop recording");
            final long requestStopCorrelationId = this.correlationId++;
            waitFor(() -> client.stopRecording(PUBLISH_URI, PUBLISH_STREAM_ID, requestStopCorrelationId));
            waitForOk(client, reply, requestStopCorrelationId);

            waitFor(() -> client.pollEvents(new FailRecordingEventsListener()
            {
                public void onStop(final int rId)
                {
                    assertThat(rId, is(recordingId));
                }
            }, 1) != 0);

            verifyDescriptorListOngoingArchive(client, publication, totalRecordingLength);

            println("Recording id: " + recordingId);
            println("Meta data file printout: ");

            validateMetaDataFile(publication);
            validateArchiveFile(messageCount, recordingId);
            validateArchiveFileChunked(messageCount, recordingId);

            validateReplay(client, publication, messageCount);
        }
    }

    private void verifyEmptyDescriptorList(final ArchiveClient client)
    {
        final long requestRecordingsCorrelationId = this.correlationId++;
        client.listRecordings(0, 100, requestRecordingsCorrelationId);
        TestUtil.waitForFail(client, reply, requestRecordingsCorrelationId);
    }

    private void verifyDescriptorListOngoingArchive(
        final ArchiveClient client,
        final Publication publication,
        final long recordingLength)
    {
        final long requestRecordingsCorrelationId = this.correlationId++;
        client.listRecordings(recordingId, recordingId, requestRecordingsCorrelationId);
        println("Await result");
        waitFor(() -> client.pollResponses(reply, new FailResponseListener()
        {
            public void onRecordingDescriptor(
                final int rId,
                final int segmentFileLength,
                final int termBufferLength,
                final long startTime,
                final int initialTermId,
                final int initialTermOffset,
                final long endTime,
                final int lastTermId,
                final int lastTermOffset,
                final String source,
                final int sessionId,
                final String channel,
                final int streamId,
                final long correlationId)
            {
                assertThat(rId, is(recordingId));
                assertThat(termBufferLength, is(publication.termBufferLength()));

                assertThat(streamId, is(PUBLISH_STREAM_ID));

                assertThat(correlationId, is(requestRecordingsCorrelationId));
            }
        }, 1) != 0);
    }

    private int prepAndSendMessages(
        final ArchiveClient client,
        final Publication publication)
        throws InterruptedException
    {
        final int messageCount = 5000 + rnd.nextInt(10000);
        fragmentLength = new int[messageCount];
        for (int i = 0; i < messageCount; i++)
        {
            final int messageLength = 64 + rnd.nextInt(MAX_FRAGMENT_SIZE - 64) - DataHeaderFlyweight.HEADER_LENGTH;
            fragmentLength[i] = messageLength + DataHeaderFlyweight.HEADER_LENGTH;
            totalDataLength += fragmentLength[i];
        }

        final CountDownLatch waitForData = new CountDownLatch(1);
        printf("Sending %d messages, total length=%d %n", messageCount, totalDataLength);

        trackRecordingProgress(client, publication.termBufferLength(), waitForData);
        publishDataToRecorded(publication, messageCount);
        waitForData.await();

        return messageCount;
    }

    private void validateMetaDataFile(final Publication publication) throws IOException
    {
        final File metaFile = new File(archiveDir, recordingMetaFileName(recordingId));
        assertTrue(metaFile.exists());

        if (TestUtil.DEBUG)
        {
            ArchiveUtil.printMetaFile(metaFile);
        }

        final RecordingDescriptorDecoder decoder = recordingMetaFileFormatDecoder(metaFile);
        assertThat(decoder.initialTermId(), is(publication.initialTermId()));
        assertThat(decoder.sessionId(), is(publication.sessionId()));
        assertThat(decoder.streamId(), is(publication.streamId()));
        assertThat(decoder.termBufferLength(), is(publication.termBufferLength()));

        assertThat(ArchiveUtil.recordingFileFullLength(decoder), is(totalRecordingLength));
        // length might exceed data sent due to padding
        assertThat(totalDataLength, lessThanOrEqualTo(totalRecordingLength));

        IoUtil.unmap(decoder.buffer().byteBuffer());
    }

    private void publishDataToRecorded(final Publication publication, final int messageCount)
    {
        final int positionBitsToShift = Integer.numberOfTrailingZeros(publication.termBufferLength());
        final long initialPosition = publication.position();
        final int initialTermOffset = LogBufferDescriptor.computeTermOffsetFromPosition(
            initialPosition, positionBitsToShift);
        // clear out the buffer we write
        for (int i = 0; i < 1024; i++)
        {
            buffer.putByte(i, (byte)'z');
        }
        buffer.putStringAscii(32, "TEST");

        for (int i = 0; i < messageCount; i++)
        {
            final int dataLength = fragmentLength[i] - DataHeaderFlyweight.HEADER_LENGTH;
            buffer.putInt(0, i);
            printf("Sending: index=%d length=%d %n", i, dataLength);
            TestUtil.offer(publication, buffer, dataLength);
        }

        final int lastTermOffset = LogBufferDescriptor.computeTermOffsetFromPosition(
            publication.position(), positionBitsToShift);
        final int termIdFromPosition = LogBufferDescriptor.computeTermIdFromPosition(
            publication.position(), positionBitsToShift, publication.initialTermId());
        totalRecordingLength =
            (termIdFromPosition - publication.initialTermId()) * publication.termBufferLength() +
                (lastTermOffset - initialTermOffset);

        assertThat(publication.position() - initialPosition, is(totalRecordingLength));
        lastTermId = termIdFromPosition;
    }

    private void validateReplay(
        final ArchiveClient client,
        final Publication publication,
        final int messageCount)
    {
        try (Subscription replay = publishingClient.addSubscription(REPLAY_URI, 101))
        {
            final long replayCorrelationId = correlationId++;
            // request replay
            waitFor(() -> client.replay(
                recordingId,
                publication.initialTermId(),
                0,
                totalRecordingLength,
                REPLAY_URI,
                101,
                correlationId++
            ));
            waitForOk(client, reply, replayCorrelationId);

            TestUtil.awaitSubscriptionIsConnected(replay);

            nextFragmentOffset = 0;
            fragmentCount = 0;
            remaining = totalDataLength;

            while (remaining > 0)
            {
                printf("Fragment [%d of %d]%n", fragmentCount + 1, fragmentLength.length);
                poll(replay, this::validateFragment2);
            }

            assertThat(fragmentCount, is(messageCount));
            assertThat(remaining, is(0L));
        }
    }

    private void validateArchiveFile(final int messageCount, final int recordingId) throws IOException
    {
        try (RecordingFragmentReader archiveDataFileReader = new RecordingFragmentReader(recordingId, archiveDir))
        {
            fragmentCount = 0;
            remaining = totalDataLength;
            archiveDataFileReader.controlledPoll(this::validateFragment1, messageCount);

            assertThat(remaining, is(0L));
            assertThat(fragmentCount, is(messageCount));
        }
    }

    private boolean validateFragment1(
        final DirectBuffer buffer,
        final int offset, final int length,
        @SuppressWarnings("unused") final DataHeaderFlyweight header)
    {
        assertThat(length, is(fragmentLength[fragmentCount] - DataHeaderFlyweight.HEADER_LENGTH));
        assertThat(buffer.getInt(offset), is(fragmentCount));
        assertThat(buffer.getByte(offset + 4), is((byte)'z'));

        remaining -= fragmentLength[fragmentCount];
        fragmentCount++;

        return true;
    }

    private void validateFragment2(
        final DirectBuffer buffer,
        final int offset, final int length,
        @SuppressWarnings("unused") final Header header)
    {
        assertThat(length, is(fragmentLength[fragmentCount] - DataHeaderFlyweight.HEADER_LENGTH));
        assertThat(buffer.getInt(offset), is(fragmentCount));
        assertThat(buffer.getByte(offset + 4), is((byte)'z'));
        remaining -= fragmentLength[fragmentCount];
        fragmentCount++;
        printf("Fragment2: offset=%d length=%d %n", offset, length);
    }

    private void validateArchiveFileChunked(final int messageCount, final int recordingId) throws IOException
    {
        final RecordingDescriptorDecoder decoder = recordingMetaFileFormatDecoder(
            new File(archiveDir, recordingMetaFileName(recordingId)));
        final long archiveFullLength = ArchiveUtil.recordingFileFullLength(decoder);
        final int initialTermId = decoder.initialTermId();
        final int termBufferLength = decoder.termBufferLength();
        final int initialTermOffset = decoder.initialTermOffset();

        IoUtil.unmap(decoder.buffer().byteBuffer());
        try (RecordingChunkReader cursor = new RecordingChunkReader(
            recordingId,
            archiveDir,
            initialTermId,
            termBufferLength,
            initialTermId,
            initialTermOffset,
            archiveFullLength,
            128 * 1024 * 1024))
        {
            fragmentCount = 0;
            final HeaderFlyweight mHeader = new HeaderFlyweight();
            nextFragmentOffset = 0;
            remaining = totalDataLength;

            while (!cursor.isDone())
            {
                cursor.readChunk(
                    (termBuffer, termOffset, chunkLength) ->
                    {
                        validateFragmentsInChunk(
                            mHeader,
                            messageCount,
                            termBuffer,
                            termOffset,
                            chunkLength);
                        return true;
                    },
                    4096 - DataHeaderFlyweight.HEADER_LENGTH);
            }
        }

        assertThat(fragmentCount, is(messageCount));
        assertThat(remaining, is(0L));
    }

    private void validateFragmentsInChunk(
        final HeaderFlyweight mHeader,
        final int messageCount,
        final DirectBuffer termBuffer,
        final int termOffset,
        final int chunkLength)
    {
        printf("Chunk: length=%d \t, offset=%d%n", chunkLength, termOffset);

        int messageStart;
        int frameLength;
        while (nextFragmentOffset < chunkLength)
        {
            messageStart = termOffset + nextFragmentOffset;
            mHeader.wrap(termBuffer, messageStart, HeaderFlyweight.HEADER_LENGTH);
            frameLength = mHeader.frameLength();

            if (mHeader.headerType() == DataHeaderFlyweight.HDR_TYPE_DATA)
            {
                assertThat("Fragments exceed messages", fragmentCount, lessThan(messageCount));
                assertThat("Fragment:" + fragmentCount, frameLength, is(fragmentLength[fragmentCount]));

                if (messageStart + 32 < termOffset + chunkLength)
                {
                    final int index = termBuffer.getInt(messageStart + DataHeaderFlyweight.HEADER_LENGTH);
                    assertThat(String.format(
                        "Fragment: length=%d, foffset=%d, getInt(0)=%d, toffset=%d",
                        frameLength, (nextFragmentOffset % chunkLength), index, termOffset),
                        index, is(fragmentCount));
                    printf("Fragment: length=%d \t, offset=%d \t, getInt(0)=%d %n",
                        frameLength, (nextFragmentOffset % chunkLength), index);
                }

                remaining -= frameLength;
                fragmentCount++;
            }

            final int alignedLength = BitUtil.align(frameLength, FRAME_ALIGNMENT);
            nextFragmentOffset += alignedLength;
        }

        nextFragmentOffset -= chunkLength;
    }

    private void trackRecordingProgress(
        final ArchiveClient client,
        final int termBufferLength,
        final CountDownLatch waitForData)
    {
        final Thread t = new Thread(
            () ->
            {
                try
                {
                    recorded = 0;
                    long start = System.currentTimeMillis();
                    long startBytes = remaining;
                    // each message is fragmentLength[fragmentCount]
                    while (lastTermId == -1 || recorded < totalRecordingLength)
                    {
                        waitFor(() -> (client.pollEvents(new RecordingEventsListener()
                        {
                            public void onProgress(
                                final int recordingId0,
                                final int initialTermId,
                                final int initialTermOffset,
                                final int termId,
                                final int termOffset)
                            {
                                assertThat(recordingId0, is(recordingId));
                                recorded = recordingLength(
                                    termBufferLength,
                                    initialTermId,
                                    initialTermOffset,
                                    termId,
                                    termOffset);
                                printf("a=%d total=%d %n", recorded, totalRecordingLength);
                            }

                            public void onStart(
                                final int recordingId,
                                final String source,
                                final int sessionId,
                                final String channel,
                                final int streamId)
                            {
                                fail();
                            }

                            public void onStop(final int recordingId0)
                            {
                                fail();
                            }
                        }, 1)) != 0);


                        final long end = System.currentTimeMillis();
                        final long deltaTime = end - start;
                        if (deltaTime > TestUtil.TIMEOUT)
                        {
                            start = end;
                            final long deltaBytes = remaining - startBytes;
                            startBytes = remaining;
                            final double mbps = ((deltaBytes * 1000.0) / deltaTime) / MEGABYTE;
                            printf("Archive reported speed: %f MB/s %n", mbps);
                        }
                    }
                    final long end = System.currentTimeMillis();
                    final long deltaTime = end - start;

                    final long deltaBytes = remaining - startBytes;
                    final double mbps = ((deltaBytes * 1000.0) / deltaTime) / MEGABYTE;
                    printf("Archive reported speed: %f MB/s %n", mbps);
                }
                catch (final Throwable throwable)
                {
                    trackerError = throwable;
                }

                waitForData.countDown();
            });

        t.setDaemon(true);
        t.start();
    }
}
