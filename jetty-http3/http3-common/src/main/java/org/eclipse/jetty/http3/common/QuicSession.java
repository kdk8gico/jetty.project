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

package org.eclipse.jetty.http3.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http3.quiche.QuicheConnection;
import org.eclipse.jetty.http3.quiche.QuicheConnectionId;
import org.eclipse.jetty.http3.quiche.ffi.LibQuiche;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.CyclicTimeout;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.strategy.EatWhatYouKill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class QuicSession
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicSession.class);

    private final Flusher flusher;
    private final Scheduler scheduler;
    private final ByteBufferPool byteBufferPool;
    private final QuicheConnection quicheConnection;
    private final QuicConnection connection;
    private final ConcurrentMap<Long, QuicStreamEndPoint> endpoints = new ConcurrentHashMap<>();
    private final ExecutionStrategy strategy;
    private final AutoLock strategyQueueLock = new AutoLock();
    private final Queue<Runnable> strategyQueue = new ArrayDeque<>();
    private InetSocketAddress remoteAddress;
    private QuicheConnectionId quicheConnectionId;

    protected QuicSession(Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, QuicheConnection quicheConnection, QuicConnection connection, InetSocketAddress remoteAddress)
    {
        this.scheduler = scheduler;
        this.byteBufferPool = byteBufferPool;
        this.quicheConnection = quicheConnection;
        this.connection = connection;
        this.remoteAddress = remoteAddress;
        this.flusher = new Flusher(scheduler);
        this.strategy = new EatWhatYouKill(() ->
        {
            try (AutoLock l = strategyQueueLock.lock())
            {
                return strategyQueue.poll();
            }
        }, executor);
        LifeCycle.start(strategy);
    }

    public Scheduler getScheduler()
    {
        return scheduler;
    }

    public String getNegotiatedProtocol()
    {
        return quicheConnection.getNegotiatedProtocol();
    }

    public void createStream(long streamId)
    {
        getOrCreateStreamEndPoint(streamId);
    }

    public int fill(long streamId, ByteBuffer buffer) throws IOException
    {
        return quicheConnection.drainClearTextForStream(streamId, buffer);
    }

    public int flush(long streamId, ByteBuffer buffer) throws IOException
    {
        int flushed = quicheConnection.feedClearTextForStream(streamId, buffer);
        flush();
        return flushed;
    }

    public void flushFinished(long streamId) throws IOException
    {
        quicheConnection.feedFinForStream(streamId);
        flush();
    }

    public boolean isFinished(long streamId)
    {
        return quicheConnection.isStreamFinished(streamId);
    }

    public void shutdownInput(long streamId) throws IOException
    {
        quicheConnection.shutdownStream(streamId, false);
    }

    public void shutdownOutput(long streamId) throws IOException
    {
        quicheConnection.shutdownStream(streamId, true);
    }

    public void onClose(long streamId)
    {
        endpoints.remove(streamId);
    }

    InetSocketAddress getLocalAddress()
    {
        return connection.getEndPoint().getLocalAddress();
    }

    public InetSocketAddress getRemoteAddress()
    {
        return remoteAddress;
    }

    public boolean isConnectionEstablished()
    {
        return quicheConnection.isConnectionEstablished();
    }

    public void setConnectionId(QuicheConnectionId quicheConnectionId)
    {
        this.quicheConnectionId = quicheConnectionId;
    }

    void process(InetSocketAddress remoteAddress, ByteBuffer cipherBufferIn) throws IOException
    {
        this.remoteAddress = remoteAddress;
        quicheConnection.feedCipherText(cipherBufferIn);

        if (quicheConnection.isConnectionEstablished())
        {
            List<Long> writableStreamIds = quicheConnection.writableStreamIds();
            if (LOG.isDebugEnabled())
                LOG.debug("writable stream ids: {}", writableStreamIds);
            Runnable onWritable = () ->
            {
                for (Long writableStreamId : writableStreamIds)
                {
                    onWritable(writableStreamId);
                }
            };
            dispatch(onWritable);

            List<Long> readableStreamIds = quicheConnection.readableStreamIds();
            if (LOG.isDebugEnabled())
                LOG.debug("readable stream ids: {}", readableStreamIds);
            for (Long readableStreamId : readableStreamIds)
            {
                Runnable onReadable = () -> onReadable(readableStreamId);
                dispatch(onReadable);
            }
        }
        else
        {
            flush();
        }
    }

    private void onWritable(long writableStreamId)
    {
        QuicStreamEndPoint streamEndPoint = getOrCreateStreamEndPoint(writableStreamId);
        if (LOG.isDebugEnabled())
            LOG.debug("selected endpoint for write: {}", streamEndPoint);
        streamEndPoint.onWritable();
    }

    private void onReadable(long readableStreamId)
    {
        QuicStreamEndPoint streamEndPoint = getOrCreateStreamEndPoint(readableStreamId);
        if (LOG.isDebugEnabled())
            LOG.debug("selected endpoint for read: {}", streamEndPoint);
        streamEndPoint.onReadable();
    }

    private void dispatch(Runnable runnable)
    {
        try (AutoLock l = strategyQueueLock.lock())
        {
            strategyQueue.offer(runnable);
        }
        strategy.dispatch();
    }

    public void flush()
    {
        flusher.iterate();
    }

    private QuicStreamEndPoint getOrCreateStreamEndPoint(long streamId)
    {
        QuicStreamEndPoint endPoint = endpoints.compute(streamId, (sid, quicStreamEndPoint) ->
        {
            if (quicStreamEndPoint == null)
            {
                quicStreamEndPoint = createQuicStreamEndPoint(streamId);
                if (LOG.isDebugEnabled())
                    LOG.debug("creating endpoint for stream {}", sid);
            }
            return quicStreamEndPoint;
        });
        if (LOG.isDebugEnabled())
            LOG.debug("returning endpoint for stream {}", streamId);
        return endPoint;
    }

    protected abstract QuicStreamEndPoint createQuicStreamEndPoint(long streamId);

    public void close()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("closing QUIC session {}", this);
        try
        {
            endpoints.values().forEach(AbstractEndPoint::close);
            endpoints.clear();
            flusher.close();
            connection.onClose(quicheConnectionId, this);
            LifeCycle.stop(strategy);
        }
        finally
        {
            // This call frees malloc'ed memory so make sure it always happens.
            quicheConnection.dispose();
        }
        if (LOG.isDebugEnabled())
            LOG.debug("closed QUIC session {}", this);
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + " id=" + quicheConnectionId;
    }

    private class Flusher extends IteratingCallback
    {
        private final CyclicTimeout timeout;
        private ByteBuffer cipherBuffer;

        public Flusher(Scheduler scheduler)
        {
            timeout = new CyclicTimeout(scheduler)
            {
                @Override
                public void onTimeoutExpired()
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("quiche timeout callback");
                    quicheConnection.onTimeout();
                    if (LOG.isDebugEnabled())
                        LOG.debug("re-iterating quiche after timeout");
                    iterate();
                }
            };
        }

        @Override
        public void close()
        {
            super.close();
            timeout.destroy();
        }

        @Override
        protected Action process() throws IOException
        {
            cipherBuffer = byteBufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
            int pos = BufferUtil.flipToFill(cipherBuffer);
            int drained = quicheConnection.drainCipherText(cipherBuffer);
            long nextTimeoutInMs = quicheConnection.nextTimeout();
            if (LOG.isDebugEnabled())
                LOG.debug("next quiche timeout: {} ms", nextTimeoutInMs);
            if (nextTimeoutInMs < 0)
                timeout.cancel();
            else
                timeout.schedule(nextTimeoutInMs, TimeUnit.MILLISECONDS);
            if (drained == 0)
            {
                if (quicheConnection.isConnectionClosed())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("quiche connection is in closed state");
                    QuicSession.this.close();
                }
                return Action.IDLE;
            }
            BufferUtil.flipToFlush(cipherBuffer, pos);
            connection.write(this, remoteAddress, cipherBuffer);
            return Action.SCHEDULED;
        }

        @Override
        public void succeeded()
        {
            byteBufferPool.release(cipherBuffer);
            super.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            byteBufferPool.release(cipherBuffer);
        }
    }
}
