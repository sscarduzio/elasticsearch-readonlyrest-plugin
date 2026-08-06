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
package tech.beshu.ror.unit.utils

import better.files.File
import io.netty.buffer.ByteBufAllocator
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.{Channel, ChannelHandler, ChannelInboundHandlerAdapter}
import io.netty.handler.ssl.{SslContextBuilder, SslHandler}
import javax.net.ssl.SSLEngine
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.utils.PeerCertificateExtractor

/** The walk from the ES `HttpChannel` down to the `SSLEngine` is reflective, so nothing about it is checked
  * at compile time. These tests pin the mechanics against a real Netty pipeline; that the ES channel really
  * does expose `getNettyChannel` can only be confirmed against a running Elasticsearch.
  */
class PeerCertificateExtractorTests extends AnyWordSpec {

  "Reaching the SSL engine of a connection" should {
    "find the handler under the name Elasticsearch gives it" in {
      val sslEngine = newSslEngine
      val httpChannel = httpChannelWith("ssl" -> new HandlerWithEngine(sslEngine))

      PeerCertificateExtractor.sslEngineOf(httpChannel) should be(Some(sslEngine))
    }
    "find the handler under the name ReadonlyREST gives it" in {
      val sslEngine = newSslEngine
      val httpChannel = httpChannelWith("ssl_netty4_handler" -> new HandlerWithEngine(sslEngine))

      PeerCertificateExtractor.sslEngineOf(httpChannel) should be(Some(sslEngine))
    }
    "find the handler by its type when it is registered under an unexpected name" in {
      val sslEngine = newSslEngine
      val httpChannel = httpChannelWith("some_other_name" -> new SslHandler(sslEngine))

      PeerCertificateExtractor.sslEngineOf(httpChannel) should be(Some(sslEngine))
    }
    "find nothing when the connection has no SSL handler" in {
      val httpChannel = httpChannelWith("plain_handler" -> new ChannelInboundHandlerAdapter())

      PeerCertificateExtractor.sslEngineOf(httpChannel) should be(None)
    }
    "find nothing when the channel doesn't expose the underlying Netty channel" in {
      PeerCertificateExtractor.sslEngineOf(new Object()) should be(None)
    }
  }

  "Reading the client certificate of a connection" should {
    "find nothing when the client presented no certificate" in {
      val httpChannel = httpChannelWith("ssl" -> new SslHandler(newSslEngine))

      PeerCertificateExtractor.clientCertificateOf(httpChannel) should be(None)
    }
    "find nothing when the connection is not encrypted" in {
      val httpChannel = httpChannelWith("plain_handler" -> new ChannelInboundHandlerAdapter())

      PeerCertificateExtractor.clientCertificateOf(httpChannel) should be(None)
    }
  }

  private def httpChannelWith(handler: (String, ChannelHandler)) = {
    val channel = new EmbeddedChannel()
    channel.pipeline().addLast(handler._1, handler._2)
    new FakeHttpChannel(channel)
  }

  private def newSslEngine: SSLEngine = {
    SslContextBuilder
      .forServer((certsDir / "pkcs8-ec-cert.pem").toJava, (certsDir / "pkcs8-ec-key.pem").toJava)
      .build()
      .newEngine(ByteBufAllocator.DEFAULT)
  }

  private lazy val certsDir = File(getClass.getResource("/ssl/"))
}

/** Stands in for `org.elasticsearch.http.netty4.Netty4HttpChannel`, which core cannot depend on. */
final class FakeHttpChannel(channel: Channel) {
  def getNettyChannel: Channel = channel
}

/** A handler that is not an `SslHandler` but exposes an engine the same way, so that the by-name lookup
  * can be told apart from the by-type fallback.
  */
final class HandlerWithEngine(sslEngine: SSLEngine) extends ChannelInboundHandlerAdapter {
  def engine: SSLEngine = sslEngine
}
