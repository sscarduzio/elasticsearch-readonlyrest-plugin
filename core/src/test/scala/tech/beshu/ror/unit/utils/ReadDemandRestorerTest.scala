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

import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.flow.FlowControlHandler
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.utils.ReadDemandRestorer

import scala.collection.mutable

class ReadDemandRestorerTest extends AnyWordSpec {

  "A ReadDemandRestorer" should {
    "deliver the message that follows a read which decoded nothing" in {
      val channel = channelWithFlowControl(withReadDemandRestorer = true)

      channel.pipeline().fireChannelReadComplete()
      channel.pipeline().fireChannelRead("chunk")

      messagesReceivedBy(channel) should be(List("chunk"))
    }

    "keep delivering messages when every read decodes one message" in {
      val channel = channelWithFlowControl(withReadDemandRestorer = true)

      channel.pipeline().fireChannelRead("first")
      channel.pipeline().fireChannelReadComplete()
      channel.read()
      channel.pipeline().fireChannelRead("second")

      messagesReceivedBy(channel) should be(List("first", "second"))
    }
  }

  "A pipeline without a ReadDemandRestorer" should {
    "stall after a read which decoded nothing" in {
      val channel = channelWithFlowControl(withReadDemandRestorer = false)

      channel.pipeline().fireChannelReadComplete()
      channel.pipeline().fireChannelRead("chunk")

      messagesReceivedBy(channel) should be(List.empty)
    }
  }

  private def channelWithFlowControl(withReadDemandRestorer: Boolean) = {
    val channel = new EmbeddedChannel()
    channel.config().setAutoRead(false)
    channel.pipeline().addLast("flow_control", new FlowControlHandler())
    channel.pipeline().addLast("recorder", new MessageRecorder())
    // the ES channel initializer asks for one read before ROR adds its handler
    channel.read()
    if (withReadDemandRestorer) {
      channel.pipeline().addAfter("flow_control", "ror_read_demand_restorer", new ReadDemandRestorer())
    }
    channel
  }

  private def messagesReceivedBy(channel: EmbeddedChannel) = {
    channel.pipeline().get(classOf[MessageRecorder]).messages.toList
  }

  private final class MessageRecorder extends ChannelInboundHandlerAdapter {
    val messages: mutable.ListBuffer[AnyRef] = mutable.ListBuffer.empty

    override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = {
      messages += msg
    }

  }

}
