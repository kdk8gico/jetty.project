//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.internal;

import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.parser.ParserListener;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HTTP3Session implements Session, ParserListener
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3Session.class);

    private final Map<Long, HTTP3Stream> streams = new ConcurrentHashMap<>();
    private final ProtocolSession session;
    private final Listener listener;
    private CloseState closeState = CloseState.CLOSED;

    public HTTP3Session(ProtocolSession session, Listener listener)
    {
        this.session = session;
        this.listener = listener;
    }

    public ProtocolSession getProtocolSession()
    {
        return session;
    }

    public Listener getListener()
    {
        return listener;
    }

    public void onOpen()
    {
        closeState = CloseState.NOT_CLOSED;
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return getProtocolSession().getQuicSession().getLocalAddress();
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return getProtocolSession().getQuicSession().getRemoteAddress();
    }

    @Override
    public boolean isClosed()
    {
        return closeState != CloseState.NOT_CLOSED;
    }

    public void close(int error, String reason)
    {
        getProtocolSession().close(error, reason);
    }

    protected HTTP3Stream createStream(QuicStreamEndPoint endPoint)
    {
        long streamId = endPoint.getStreamId();
        HTTP3Stream stream = new HTTP3Stream(this, endPoint);
        if (streams.put(streamId, stream) != null)
            throw new IllegalStateException("duplicate stream id " + streamId);
        return stream;
    }

    protected HTTP3Stream getOrCreateStream(QuicStreamEndPoint endPoint)
    {
        return streams.computeIfAbsent(endPoint.getStreamId(), id -> new HTTP3Stream(this, endPoint));
    }

    protected HTTP3Stream getStream(long streamId)
    {
        return streams.get(streamId);
    }

    public abstract void writeFrame(long streamId, Frame frame, Callback callback);

    public Map<Long, Long> onPreface()
    {
        Map<Long, Long> settings = notifyPreface();
        if (LOG.isDebugEnabled())
            LOG.debug("produced settings {} on {}", settings, this);
        return settings;
    }

    private Map<Long, Long> notifyPreface()
    {
        try
        {
            return listener.onPreface(this);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
            return null;
        }
    }

    @Override
    public void onSettings(SettingsFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("received {} on {}", frame, this);
        notifySettings(frame);
    }

    private void notifySettings(SettingsFrame frame)
    {
        try
        {
            listener.onSettings(this, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    @Override
    public void onHeaders(long streamId, HeadersFrame frame)
    {
        QuicStreamEndPoint endPoint = session.getStreamEndPoint(streamId);
        HTTP3Stream stream = getOrCreateStream(endPoint);
        MetaData metaData = frame.getMetaData();
        if (metaData.isRequest() || metaData.isResponse())
        {
            throw new IllegalStateException("invalid metadata");
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("received trailer {}#{} on {}", frame, streamId, this);
            stream.processTrailer(frame);
        }
    }

    @Override
    public void onData(long streamId, DataFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("received {}#{} on {}", frame, streamId, this);
        HTTP3Stream stream = getStream(streamId);
        if (stream != null)
            stream.processData(frame);
        else
            closeAndNotifyFailure(ErrorCode.FRAME_UNEXPECTED_ERROR.code(), "invalid_frame_sequence");
    }

    public void onDataAvailable(long streamId)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("notifying data available for stream #{} on {}", streamId, this);
        HTTP3Stream stream = getStream(streamId);
        notifyDataAvailable(stream);
    }

    private void notifyDataAvailable(HTTP3Stream stream)
    {
        Stream.Listener listener = stream.getListener();
        try
        {
            if (listener != null)
                listener.onDataAvailable(stream);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    void closeAndNotifyFailure(int error, String reason)
    {
        close(error, reason);
        notifySessionFailure(error, reason);
    }

    public void notifySessionFailure(int error, String reason)
    {
        try
        {
            listener.onSessionFailure(this, error, reason);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }

    private enum CloseState
    {
        NOT_CLOSED, CLOSED
    }
}
