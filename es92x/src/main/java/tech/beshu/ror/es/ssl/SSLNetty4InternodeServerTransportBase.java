/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.es.ssl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.ConnectionProfile;
import org.elasticsearch.transport.netty4.Netty4Transport;
import org.elasticsearch.transport.netty4.SharedGroupFactory;

/**
 * Java base class to work around Scala 3.3.7 limitation with protected Java inner class constructors.
 * Scala 3.3.7 cannot access protected inner class constructors from Java parent classes.
 * This was not an issue in Scala 3.3.3.
 */
public abstract class SSLNetty4InternodeServerTransportBase extends Netty4Transport {

    public SSLNetty4InternodeServerTransportBase(Settings settings,
                                                 ThreadPool threadPool,
                                                 PageCacheRecycler pageCacheRecycler,
                                                 CircuitBreakerService circuitBreakerService,
                                                 NamedWriteableRegistry namedWriteableRegistry,
                                                 NetworkService networkService,
                                                 SharedGroupFactory sharedGroupFactory) {
        super(settings, TransportVersion.current(), threadPool, networkService, pageCacheRecycler,
                namedWriteableRegistry, circuitBreakerService, sharedGroupFactory);
    }

    @Override
    protected ChannelHandler getClientChannelInitializer(DiscoveryNode node, ConnectionProfile connectionProfile) {
        return new SSLClientChannelInitializer(node);
    }

    @Override
    protected ChannelHandler getServerChannelInitializer(String name) {
        return new SSLServerChannelInitializer(name);
    }

    // Abstract methods to be implemented in Scala
    protected abstract void initClientChannel(Channel ch, DiscoveryNode node) throws Exception;
    protected abstract void onClientChannelException(ChannelHandlerContext ctx, Throwable cause) throws Exception;
    protected abstract void initServerChannel(Channel ch) throws Exception;

    private class SSLClientChannelInitializer extends ClientChannelInitializer {
        private final DiscoveryNode node;

        SSLClientChannelInitializer(DiscoveryNode node) {
            this.node = node;
        }

        @Override
        protected void initChannel(Channel ch) throws Exception {
            super.initChannel(ch);
            SSLNetty4InternodeServerTransportBase.this.initClientChannel(ch, node);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            SSLNetty4InternodeServerTransportBase.this.onClientChannelException(ctx, cause);
        }
    }

    private class SSLServerChannelInitializer extends ServerChannelInitializer {
        SSLServerChannelInitializer(String name) {
            super(name);
        }

        @Override
        protected void initChannel(Channel ch) throws Exception {
            super.initChannel(ch);
            SSLNetty4InternodeServerTransportBase.this.initServerChannel(ch);
        }
    }
}
