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
import io.netty.channel.{
  ChannelDuplexHandler,
  ChannelHandler,
  ChannelHandlerContext,
  ChannelInboundHandlerAdapter,
  ChannelOutboundHandlerAdapter,
  ChannelPipeline
}
import io.netty.handler.flow.FlowControlHandler
import org.apache.logging.log4j.Level
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.utils.LogEventsCapture.captureLogEvents
import tech.beshu.ror.utils.ReadDemandRestorer

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

class ReadDemandRestorerTest extends AnyWordSpec {

  "A ReadDemandRestorer" should {
    "ask for another read, and deliver the message that follows a read which decoded nothing" in {
      val channel = channelWithFlowControl(withReadDemandRestorer = true)

      channel.pipeline().fireChannelReadComplete()
      channel.pipeline().fireChannelRead("chunk")

      messagesReceivedBy(channel) should be(List("chunk"))
      readsReachingHeadOf(channel) should be(initialReads + 1)
    }

    "ask for no further read when the message arrives" in {
      val channel = channelWithFlowControl(withReadDemandRestorer = true)

      channel.pipeline().fireChannelRead("chunk")
      channel.pipeline().fireChannelReadComplete()

      messagesReceivedBy(channel) should be(List("chunk"))
      readsReachingHeadOf(channel) should be(initialReads)
    }
  }

  "A pipeline without a ReadDemandRestorer" should {
    "stall after a read which decoded nothing" in {
      val channel = channelWithFlowControl(withReadDemandRestorer = false)

      channel.pipeline().fireChannelReadComplete()
      channel.pipeline().fireChannelRead("chunk")

      messagesReceivedBy(channel) should be(List.empty)
      readsReachingHeadOf(channel) should be(initialReads)
    }
  }

  "ReadDemandRestorer.installIn" should {
    "add the handler directly after the netty FlowControlHandler" in {
      val pipeline = pipelineWith("flow_control" -> new FlowControlHandler(), "recorder" -> new MessageRecorder())

      val events = captureLogEvents(installerLoggerName) {
        ReadDemandRestorer.installIn(pipeline)
      }

      handlerNamesOf(pipeline) should be(List("flow_control", "ror_read_demand_restorer", "recorder"))
      events should be(List.empty)
    }

    "add no handler and log nothing when the pipeline has the ES flow control handler" in {
      val pipeline =
        pipelineWith("decoder_flow_control" -> new ChannelDuplexHandler(), "recorder" -> new MessageRecorder())

      val events = captureLogEvents(installerLoggerName) {
        ReadDemandRestorer.installIn(pipeline)
      }

      handlerNamesOf(pipeline) should be(List("decoder_flow_control", "recorder"))
      events should be(List.empty)
    }

    "add no handler and log a warning when the pipeline has no flow control handler" in {
      val pipeline = pipelineWith("recorder" -> new MessageRecorder())

      val events = captureLogEvents(installerLoggerName) {
        ReadDemandRestorer.installIn(pipeline)
      }

      handlerNamesOf(pipeline) should be(List("recorder"))
      events.map(_.level) should be(List(Level.WARN))
    }
  }

  // the ES channel initializer asks for one read before ROR adds its handler
  private lazy val initialReads = 1

  private lazy val installerLoggerName = ReadDemandRestorer.getClass.getName

  private def pipelineWith(handlers: (String, ChannelHandler)*) = {
    val pipeline = new EmbeddedChannel().pipeline()
    handlers.foreach { case (name, handler) => pipeline.addLast(name, handler) }
    pipeline
  }

  private def handlerNamesOf(pipeline: ChannelPipeline) = {
    pipeline.toMap.keySet.asScala.toList
  }

  private def channelWithFlowControl(withReadDemandRestorer: Boolean) = {
    val channel = new EmbeddedChannel()
    channel.config().setAutoRead(false)
    channel.pipeline().addLast("read_counter", new ReadCounter())
    channel.pipeline().addLast("flow_control", new FlowControlHandler())
    channel.pipeline().addLast("recorder", new MessageRecorder())
    channel.read()
    if (withReadDemandRestorer) {
      channel.pipeline().addAfter("flow_control", "ror_read_demand_restorer", new ReadDemandRestorer())
    }
    channel
  }

  private def messagesReceivedBy(channel: EmbeddedChannel) = {
    channel.pipeline().get(classOf[MessageRecorder]).messages.toList
  }

  // EmbeddedChannel.read() does nothing, so only a handler can count the reads that reach the head
  private def readsReachingHeadOf(channel: EmbeddedChannel) = {
    channel.pipeline().get(classOf[ReadCounter]).reads
  }

  private final class MessageRecorder extends ChannelInboundHandlerAdapter {
    val messages: mutable.ListBuffer[AnyRef] = mutable.ListBuffer.empty

    override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = {
      messages += msg
    }

  }

  private final class ReadCounter extends ChannelOutboundHandlerAdapter {
    var reads: Int = 0

    override def read(ctx: ChannelHandlerContext): Unit = {
      reads += 1
      ctx.read()
    }

  }

}
