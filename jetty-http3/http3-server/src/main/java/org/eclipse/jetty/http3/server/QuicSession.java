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

package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
 import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http3.quiche.QuicheConnection;
import org.eclipse.jetty.http3.quiche.QuicheConnectionId;
import org.eclipse.jetty.http3.quiche.ffi.LibQuiche;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.CyclicTimeout;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicSession
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicSession.class);

    private final Flusher flusher;
    private final Connector connector;
    private final QuicheConnectionId quicheConnectionId;
    private final QuicheConnection quicheConnection;
    private final QuicConnection connection;
    private final ConcurrentMap<Long, QuicStreamEndPoint> endpoints = new ConcurrentHashMap<>();
    private InetSocketAddress remoteAddress;

    QuicSession(Connector connector, QuicheConnectionId quicheConnectionId, QuicheConnection quicheConnection, QuicConnection connection, InetSocketAddress remoteAddress)
    {
        this.connector = connector;
        this.quicheConnectionId = quicheConnectionId;
        this.quicheConnection = quicheConnection;
        this.connection = connection;
        this.remoteAddress = remoteAddress;
        this.flusher = new Flusher(connector.getScheduler());
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

    public boolean isFinished(long streamId)
    {
        return quicheConnection.isStreamFinished(streamId);
    }

    public void sendFinished(long streamId) throws IOException
    {
        quicheConnection.feedFinForStream(streamId);
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

    InetSocketAddress getRemoteAddress()
    {
        return remoteAddress;
    }

    void process(InetSocketAddress remoteAddress, ByteBuffer cipherBufferIn) throws IOException
    {
        this.remoteAddress = remoteAddress;
        quicheConnection.feedCipherText(cipherBufferIn);
        flush();

        if (quicheConnection.isConnectionEstablished())
        {
            List<Long> writableStreamIds = quicheConnection.writableStreamIds();
            if (LOG.isDebugEnabled())
                LOG.debug("writable stream ids: {}", writableStreamIds);
            for (Long writableStreamId : writableStreamIds)
            {
                onWritable(writableStreamId);
            }

            List<Long> readableStreamIds = quicheConnection.readableStreamIds();
            if (LOG.isDebugEnabled())
                LOG.debug("readable stream ids: {}", readableStreamIds);
            for (Long readableStreamId : readableStreamIds)
            {
                onReadable(readableStreamId);
            }
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
        Runnable runnable = streamEndPoint.onReadable();
        // TODO: run with EWYK
        runnable.run();
    }

    void flush()
    {
        flusher.iterate();
    }

    private QuicStreamEndPoint getOrCreateStreamEndPoint(long streamId)
    {
        QuicStreamEndPoint endPoint = endpoints.compute(streamId, (sid, quicStreamEndPoint) ->
        {
            if (quicStreamEndPoint == null)
            {
                quicStreamEndPoint = createQuicStreamEndPoint(connector, connector.getScheduler(), streamId);
                if (LOG.isDebugEnabled())
                    LOG.debug("creating endpoint for stream {}", sid);
            }
            return quicStreamEndPoint;
        });
        if (LOG.isDebugEnabled())
            LOG.debug("returning endpoint for stream {}", streamId);
        return endPoint;
    }

    private QuicStreamEndPoint createQuicStreamEndPoint(Connector connector, Scheduler scheduler, long streamId)
    {
        String negotiatedProtocol = quicheConnection.getNegotiatedProtocol();
        ConnectionFactory connectionFactory = connector.getConnectionFactory(negotiatedProtocol);
        if (connectionFactory == null)
            connectionFactory = connector.getDefaultConnectionFactory();
        if (connectionFactory == null)
            throw new RuntimeIOException("No configured connection factory can handle protocol '" + negotiatedProtocol + "'");

        QuicStreamEndPoint endPoint = new QuicStreamEndPoint(scheduler, this, streamId);
        Connection connection = connectionFactory.newConnection(connector, endPoint);
        endPoint.setConnection(connection);
        endPoint.onOpen();
        connection.onOpen();
        return endPoint;
    }

    private void close()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("closing QUIC session {}", this);
        endpoints.values().forEach(AbstractEndPoint::close);
        endpoints.clear();
        flusher.close();
        quicheConnection.dispose();
        connection.onClose(quicheConnectionId);
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
        private ByteBuffer addressBuffer;
        private ByteBuffer cipherBuffer;

        public Flusher(Scheduler scheduler)
        {
            timeout = new CyclicTimeout(scheduler) {
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
            ByteBufferPool byteBufferPool = connector.getByteBufferPool();
            addressBuffer = AddressCodec.encodeInetSocketAddress(byteBufferPool, remoteAddress);
            cipherBuffer = byteBufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN + AddressCodec.ENCODED_ADDRESS_LENGTH, true);
            int pos = BufferUtil.flipToFill(cipherBuffer);
            int drained = quicheConnection.drainCipherText(cipherBuffer);
            long nextTimeoutInMs = quicheConnection.nextTimeout();
            if (LOG.isDebugEnabled())
                LOG.debug("next quiche timeout: {} ms", nextTimeoutInMs);
            if (nextTimeoutInMs > -1)
                timeout.schedule(nextTimeoutInMs, TimeUnit.MILLISECONDS); // TODO is this re-scheduling cancelling the previous timeout?
            else
                timeout.cancel();
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
            connection.write(this, addressBuffer, cipherBuffer);
            return Action.SCHEDULED;
        }

        @Override
        public void succeeded()
        {
            ByteBufferPool byteBufferPool = connector.getByteBufferPool();
            byteBufferPool.release(addressBuffer);
            byteBufferPool.release(cipherBuffer);
            super.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            ByteBufferPool byteBufferPool = connector.getByteBufferPool();
            byteBufferPool.release(addressBuffer);
            byteBufferPool.release(cipherBuffer);
        }
    }
}
