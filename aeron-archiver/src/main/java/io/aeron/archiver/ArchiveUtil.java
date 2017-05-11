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

import io.aeron.archiver.codecs.RecordingDescriptorDecoder;
import org.agrona.IoUtil;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Date;

import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

public class ArchiveUtil
{
    static String recordingMetaFileName(final int recordingId)
    {
        return recordingId + ".inf";
    }

    static String recordingDataFileName(final int recordingId, final int segmentIndex)
    {
        return recordingId + "." + segmentIndex + ".rec";
    }

    static String recordingDataFileName(
        final int recordingId,
        final int initialTermId,
        final int termBufferLength,
        final int termId,
        final int segmentFileLength)
    {
        final int segmentIndex = segmentFileIndex(initialTermId, termBufferLength, termId, segmentFileLength);

        return recordingDataFileName(recordingId, segmentIndex);
    }

    static int segmentFileIndex(
        final int initialTermId,
        final int termBufferLength,
        final int termId,
        final int segmentFileLength)
    {
        final int termsPerFile = segmentFileLength / termBufferLength;
        return (termId - initialTermId) / termsPerFile;
    }

    static void printMetaFile(final File metaFile) throws IOException
    {
        final RecordingDescriptorDecoder formatDecoder = recordingMetaFileFormatDecoder(metaFile);
        System.out.println("recordingId: " + formatDecoder.recordingId());
        System.out.println("termBufferLength: " + formatDecoder.termBufferLength());
        System.out.println("start time: " + new Date(formatDecoder.startTime()));
        System.out.println("initialTermId: " + formatDecoder.initialTermId());
        System.out.println("initial term offset: " + formatDecoder.initialTermOffset());
        System.out.println("last term: " + formatDecoder.lastTermId());
        System.out.println("last term offset: " + formatDecoder.lastTermOffset());
        System.out.println("end time: " + new Date(formatDecoder.endTime()));
        System.out.println("source: " + formatDecoder.source());
        System.out.println("sessionId: " + formatDecoder.sessionId());
        System.out.println("channel: " + formatDecoder.channel());
        System.out.println("streamId: " + formatDecoder.streamId());
        IoUtil.unmap(formatDecoder.buffer().byteBuffer());
    }

    static RecordingDescriptorDecoder recordingMetaFileFormatDecoder(final File metaFile)
        throws IOException
    {
        try (FileChannel metadataFileChannel = FileChannel.open(metaFile.toPath(), READ, WRITE))
        {
            final MappedByteBuffer metaDataBuffer = metadataFileChannel.map(
                FileChannel.MapMode.READ_WRITE, 0, Catalog.RECORD_LENGTH);
            final RecordingDescriptorDecoder decoder = new RecordingDescriptorDecoder();

            return decoder.wrap(
                new UnsafeBuffer(metaDataBuffer),
                Catalog.CATALOG_FRAME_LENGTH,
                RecordingDescriptorDecoder.BLOCK_LENGTH,
                RecordingDescriptorDecoder.SCHEMA_VERSION);
        }
    }

    static int offsetInSegmentFile(
        final int termOffset,
        final int termId,
        final int initialTermId,
        final int termBufferLength,
        final int segmentFileLength)
    {
        final int termsMask = ((segmentFileLength / termBufferLength) - 1);
        return recordingOffset(termOffset, termId, initialTermId, termsMask, termBufferLength);
    }

    static int recordingOffset(
        final int termOffset,
        final int termId,
        final int initialTermId,
        final int termsMask,
        final int termBufferLength)
    {
        return ((termId - initialTermId) & termsMask) * termBufferLength + termOffset;
    }

    static long recordingFileFullLength(final RecordingDescriptorDecoder metaDecoder)
    {
        final int termBufferLength = metaDecoder.termBufferLength();
        final int initialTermId = metaDecoder.initialTermId();
        final int initialTermOffset = metaDecoder.initialTermOffset();
        final int lastTermId = metaDecoder.lastTermId();
        final int lastTermOffset = metaDecoder.lastTermOffset();
        return recordingLength(termBufferLength, initialTermId, initialTermOffset, lastTermId, lastTermOffset);
    }

    public static long recordingLength(
        final int termBufferLength,
        final int initialTermId,
        final int initialTermOffset,
        final int lastTermId, final int lastTermOffset)
    {
        return ((long)(lastTermId - initialTermId)) * termBufferLength +
            (lastTermOffset - initialTermOffset);
    }
}
