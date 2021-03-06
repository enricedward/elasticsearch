/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.http.nio;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.http.HttpHandlingSettings;
import org.elasticsearch.http.HttpPipelinedRequest;
import org.elasticsearch.http.nio.cors.NioCorsConfig;
import org.elasticsearch.http.nio.cors.NioCorsHandler;
import org.elasticsearch.nio.FlushOperation;
import org.elasticsearch.nio.InboundChannelBuffer;
import org.elasticsearch.nio.ReadWriteHandler;
import org.elasticsearch.nio.SocketChannelContext;
import org.elasticsearch.nio.WriteOperation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public class HttpReadWriteHandler implements ReadWriteHandler {

    private final NettyAdaptor adaptor;
    private final NioHttpChannel nioHttpChannel;
    private final NioHttpServerTransport transport;

    public HttpReadWriteHandler(NioHttpChannel nioHttpChannel, NioHttpServerTransport transport, HttpHandlingSettings settings,
                                NioCorsConfig corsConfig) {
        this.nioHttpChannel = nioHttpChannel;
        this.transport = transport;

        List<ChannelHandler> handlers = new ArrayList<>(5);
        HttpRequestDecoder decoder = new HttpRequestDecoder(settings.getMaxInitialLineLength(), settings.getMaxHeaderSize(),
            settings.getMaxChunkSize());
        decoder.setCumulator(ByteToMessageDecoder.COMPOSITE_CUMULATOR);
        handlers.add(decoder);
        handlers.add(new HttpContentDecompressor());
        handlers.add(new HttpResponseEncoder());
        handlers.add(new HttpObjectAggregator(settings.getMaxContentLength()));
        if (settings.isCompression()) {
            handlers.add(new HttpContentCompressor(settings.getCompressionLevel()));
        }
        if (settings.isCorsEnabled()) {
            handlers.add(new NioCorsHandler(corsConfig));
        }
        handlers.add(new NioHttpPipeliningHandler(transport.getLogger(), settings.getPipeliningMaxEvents()));

        adaptor = new NettyAdaptor(handlers.toArray(new ChannelHandler[0]));
        adaptor.addCloseListener((v, e) -> nioHttpChannel.close());
    }

    @Override
    public int consumeReads(InboundChannelBuffer channelBuffer) throws IOException {
        int bytesConsumed = adaptor.read(channelBuffer.sliceAndRetainPagesTo(channelBuffer.getIndex()));
        Object message;
        while ((message = adaptor.pollInboundMessage()) != null) {
            handleRequest(message);
        }

        return bytesConsumed;
    }

    @Override
    public WriteOperation createWriteOperation(SocketChannelContext context, Object message, BiConsumer<Void, Exception> listener) {
        assert message instanceof NioHttpResponse : "This channel only supports messages that are of type: "
            + NioHttpResponse.class + ". Found type: " + message.getClass() + ".";
        return new HttpWriteOperation(context, (NioHttpResponse) message, listener);
    }

    @Override
    public List<FlushOperation> writeToBytes(WriteOperation writeOperation) {
        adaptor.write(writeOperation);
        return pollFlushOperations();
    }

    @Override
    public List<FlushOperation> pollFlushOperations() {
        ArrayList<FlushOperation> copiedOperations = new ArrayList<>(adaptor.getOutboundCount());
        FlushOperation flushOperation;
        while ((flushOperation = adaptor.pollOutboundOperation()) != null) {
            copiedOperations.add(flushOperation);
        }
        return copiedOperations;
    }

    @Override
    public void close() throws IOException {
        try {
            adaptor.close();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleRequest(Object msg) {
        final HttpPipelinedRequest<FullHttpRequest> pipelinedRequest = (HttpPipelinedRequest<FullHttpRequest>) msg;
        FullHttpRequest request = pipelinedRequest.getRequest();

        try {
            final FullHttpRequest copiedRequest =
                new DefaultFullHttpRequest(
                    request.protocolVersion(),
                    request.method(),
                    request.uri(),
                    Unpooled.copiedBuffer(request.content()),
                    request.headers(),
                    request.trailingHeaders());

            NioHttpRequest httpRequest = new NioHttpRequest(copiedRequest, pipelinedRequest.getSequence());

            if (request.decoderResult().isFailure()) {
                Throwable cause = request.decoderResult().cause();
                if (cause instanceof Error) {
                    ExceptionsHelper.dieOnError(cause);
                    transport.incomingRequestError(httpRequest, nioHttpChannel, new Exception(cause));
                } else {
                    transport.incomingRequestError(httpRequest, nioHttpChannel, (Exception) cause);
                }
            } else {
                transport.incomingRequest(httpRequest, nioHttpChannel);
            }
        } finally {
            // As we have copied the buffer, we can release the request
            request.release();
        }
    }
}
