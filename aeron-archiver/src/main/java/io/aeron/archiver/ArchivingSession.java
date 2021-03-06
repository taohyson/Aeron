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
import org.agrona.*;
import org.agrona.concurrent.EpochClock;

import java.io.*;
import java.nio.ByteBuffer;

/**
 * Consumes an {@link Image} and archives data into file using {@link ArchiveStreamWriter}.
 */
class ArchivingSession implements ArchiveConductor.Session
{
    private enum State
    {
        ARCHIVING, CLOSING, DONE
    }

    private final int streamInstanceId;
    private final ArchiverProtocolProxy proxy;
    private final Image image;
    private final ArchiveIndex index;
    private final ArchiveStreamWriter writer;

    private State state = State.ARCHIVING;

    ArchivingSession(
        final ArchiverProtocolProxy proxy,
        final ArchiveIndex index,
        final File archiveFolder,
        final Image image,
        final EpochClock epochClock)
    {
        this.proxy = proxy;
        this.image = image;

        final Subscription subscription = image.subscription();
        final int streamId = subscription.streamId();
        final String channel = subscription.channel();
        final int sessionId = image.sessionId();
        final String source = image.sourceIdentity();
        final int termBufferLength = image.termBufferLength();

        final int imageInitialTermId = image.initialTermId();
        this.index = index;
        streamInstanceId = index.addNewStreamInstance(
            new StreamKey(source, sessionId, channel, streamId),
            termBufferLength,
            imageInitialTermId);

        proxy.notifyArchiveStarted(
            streamInstanceId,
            source,
            sessionId,
            channel,
            streamId);

        ArchiveStreamWriter writer = null;
        try
        {
            writer = new ArchiveStreamWriter(
                archiveFolder,
                epochClock,
                streamInstanceId,
                termBufferLength,
                imageInitialTermId,
                new StreamKey(source, sessionId, channel, streamId));
        }
        catch (final Exception ex)
        {
            close();
            LangUtil.rethrowUnchecked(ex);
        }

        this.writer = writer;
    }

    public void abort()
    {
        this.state = State.CLOSING;
    }

    public int doWork()
    {
        int workDone = 0;
        if (state == State.ARCHIVING)
        {
            workDone += archive();
        }

        if (state == State.CLOSING)
        {
            workDone += close();
        }

        return workDone;
    }

    int streamInstanceId()
    {
        return writer.streamInstanceId();
    }

    private int close()
    {
        try
        {
            if (writer != null)
            {
                writer.stop();
                index.updateIndexFromMeta(streamInstanceId, writer.metaDataBuffer());
            }
        }
        catch (final IOException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }
        finally
        {
            CloseHelper.quietClose(writer);
            proxy.notifyArchiveStopped(streamInstanceId);
            this.state = State.DONE;
        }

        return 1;
    }

    private int archive()
    {
        int workCount = 1;
        try
        {
            // TODO: add CRC as option, per fragment, use session id to store CRC
            workCount = image.rawPoll(writer, ArchiveFileUtil.ARCHIVE_FILE_SIZE);
            if (workCount != 0)
            {
                proxy.notifyArchiveProgress(
                    writer.streamInstanceId(),
                    writer.initialTermId(),
                    writer.initialTermOffset(),
                    writer.lastTermId(),
                    writer.lastTermOffset());
            }

            if (image.isClosed())
            {
                state = State.CLOSING;
            }
        }
        catch (final Exception ex)
        {
            state = State.CLOSING;
            LangUtil.rethrowUnchecked(ex);
        }

        return workCount;
    }

    public boolean isDone()
    {
        return state == State.DONE;
    }

    public void remove(final ArchiveConductor conductor)
    {
        conductor.removeArchivingSession(streamInstanceId);
    }

    ByteBuffer metaDataBuffer()
    {
        return writer.metaDataBuffer();
    }
}
