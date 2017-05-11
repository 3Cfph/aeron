/*
 * Copyright 2014-2017 Real Logic Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package io.aeron.archiver;

import io.aeron.*;
import io.aeron.archiver.codecs.RecordingDescriptorDecoder;
import io.aeron.logbuffer.RawBlockHandler;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.*;
import org.agrona.concurrent.*;
import org.junit.*;
import org.mockito.Mockito;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import static io.aeron.archiver.ArchiveUtil.recordingMetaFileName;
import static java.nio.file.StandardOpenOption.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RecordingSessionTest
{
    private static final int SEGMENT_FILE_SIZE = 128 * 1024 * 1024;
    private final int recordingId = 12345;

    private final String channel = "channel";
    private final String source = "sourceIdentity";
    private final int streamId = 54321;
    private final int sessionId = 12345;

    private final int initialTermId = 0;
    private final int termBufferLength = 4096;
    private final int termOffset = 1024;

    private final File tempDirForTest = TestUtil.makeTempDir();
    private final NotificationsProxy proxy;

    private final Image image;
    private final Catalog index;

    private FileChannel mockLogBufferChannel;
    private UnsafeBuffer mockLogBufferMapped;
    private File termFile;

    public RecordingSessionTest() throws IOException
    {
        proxy = mock(NotificationsProxy.class);
        index = mock(Catalog.class);
        when(
            index.addNewRecording(
                eq(source),
                eq(sessionId),
                eq(channel),
                eq(streamId),
                eq(termBufferLength),
                eq(initialTermId),
                any(RecordingSession.class),
                eq(SEGMENT_FILE_SIZE)))
                .thenReturn(recordingId);
        final Subscription subscription = mockSubscription(channel, streamId);
        image = mockImage(source, sessionId, initialTermId, termBufferLength, subscription);
    }

    @Before
    public void setupMockTermBuff() throws IOException
    {
        termFile = File.createTempFile("archiver.test", "source");
        // size this file as a mock term buffer
        mockLogBufferChannel = FileChannel.open(termFile.toPath(), CREATE, READ, WRITE);
        mockLogBufferChannel.position(termBufferLength - 1);
        mockLogBufferChannel.write(ByteBuffer.wrap(new byte[1]));

        // write some data at term offset
        final ByteBuffer bb = ByteBuffer.allocate(100);
        mockLogBufferChannel.position(termOffset);
        mockLogBufferChannel.write(bb);
        mockLogBufferMapped = new UnsafeBuffer(
            mockLogBufferChannel.map(FileChannel.MapMode.READ_WRITE, 0, termBufferLength));

        // prep a single message in the log buffer
        final DataHeaderFlyweight headerFlyweight = new DataHeaderFlyweight();
        headerFlyweight.wrap(mockLogBufferMapped);
        headerFlyweight.headerType(DataHeaderFlyweight.HDR_TYPE_DATA).frameLength(100);
    }

    @After
    public void teardownMockTermBuff()
    {
        IoUtil.unmap(mockLogBufferMapped.byteBuffer());
        CloseHelper.close(mockLogBufferChannel);
        IoUtil.delete(tempDirForTest, false);
        IoUtil.delete(termFile, false);
    }

    @Test
    public void shouldRecordFragmentsFromImage() throws Exception
    {
        final EpochClock epochClock = Mockito.mock(EpochClock.class);
        when(epochClock.time()).thenReturn(42L);

        final ImageRecorder.Builder builder = new ImageRecorder.Builder()
            .recordingFileLength(SEGMENT_FILE_SIZE)
            .archiveDir(tempDirForTest)
            .epochClock(epochClock);
        final RecordingSession session = new RecordingSession(proxy, index, image, builder);

        // pre-init
        assertEquals(Catalog.NULL_INDEX, session.recordingId());

        session.doWork();

        assertEquals(recordingId, session.recordingId());

        // setup the mock image to pass on the mock log buffer
        when(image.rawPoll(any(), anyInt())).thenAnswer(
            (invocation) ->
            {
                final RawBlockHandler handle = invocation.getArgument(0);
                if (handle == null)
                {
                    return 0;
                }

                handle.onBlock(
                    mockLogBufferChannel,
                    0,
                    mockLogBufferMapped,
                    termOffset,
                    100,
                    sessionId,
                    0);

                return 100;
            });

        // expecting session to proxy the available data from the image
        assertNotEquals("Expect some work", 0, session.doWork());

        // We now evaluate the output of the archiver...

        // meta data exists and is as expected
        final File recordingMetaFile = new File(tempDirForTest, recordingMetaFileName(session.recordingId()));
        assertTrue(recordingMetaFile.exists());

        final RecordingDescriptorDecoder metaData = ArchiveUtil.recordingMetaFileFormatDecoder(recordingMetaFile);

        assertEquals(recordingId, metaData.recordingId());
        assertEquals(termBufferLength, metaData.termBufferLength());
        assertEquals(initialTermId, metaData.initialTermId());
        assertEquals(termOffset, metaData.initialTermOffset());
        assertEquals(initialTermId, metaData.lastTermId());
        assertEquals(streamId, metaData.streamId());
        assertEquals(termOffset + 100, metaData.lastTermOffset());
        assertEquals(42L, metaData.startTime());
        assertEquals(-1L, metaData.endTime());
        assertEquals(source, metaData.source());
        assertEquals(channel, metaData.channel());


        // data exists and is as expected
        final File segmentFile = new File(
            tempDirForTest, ArchiveUtil.recordingDataFileName(session.recordingId(), 0));
        assertTrue(segmentFile.exists());

        try (RecordingFragmentReader reader = new RecordingFragmentReader(session.recordingId(), tempDirForTest))
        {
            final int polled = reader.controlledPoll(
                (buffer, offset, length, header) ->
                {
                    assertEquals(100, header.frameLength());
                    assertEquals(termOffset + DataHeaderFlyweight.HEADER_LENGTH, offset);
                    assertEquals(100 - DataHeaderFlyweight.HEADER_LENGTH, length);
                    return true;
                },
                1);

            assertEquals(1, polled);
        }

        // next poll has no data
        when(image.rawPoll(any(), anyInt())).thenReturn(0);
        assertEquals("Expect no work", 0, session.doWork());

        // image is closed
        when(image.isClosed()).thenReturn(true);
        when(epochClock.time()).thenReturn(128L);

        assertNotEquals("Expect some work", 0, session.doWork());
        assertTrue(session.isDone());
        assertEquals(128L, metaData.endTime());
        IoUtil.unmap(metaData.buffer().byteBuffer());
    }

    private Subscription mockSubscription(final String channel, final int streamId)
    {
        final Subscription subscription = mock(Subscription.class);
        when(subscription.channel()).thenReturn(channel);
        when(subscription.streamId()).thenReturn(streamId);

        return subscription;
    }

    private Image mockImage(
        final String sourceIdentity,
        final int sessionId,
        final int initialTermId,
        final int termBufferLength,
        final Subscription subscription)
    {
        final Image image = mock(Image.class);

        when(image.sessionId()).thenReturn(sessionId);
        when(image.sourceIdentity()).thenReturn(sourceIdentity);
        when(image.subscription()).thenReturn(subscription);
        when(image.initialTermId()).thenReturn(initialTermId);
        when(image.termBufferLength()).thenReturn(termBufferLength);

        return image;
    }
}
