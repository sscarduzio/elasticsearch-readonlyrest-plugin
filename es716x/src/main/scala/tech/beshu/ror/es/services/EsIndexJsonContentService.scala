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
package tech.beshu.ror.es.services

import java.util
import cats.implicits._
import com.google.common.collect.Maps
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.{ResourceNotFoundException, Version}
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.inject.{Inject, Singleton}
import org.elasticsearch.common.io.stream.BytesStreamOutput
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.xcontent.XContentType
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.boot.RorSchedulers
import tech.beshu.ror.es.IndexJsonContentService
import tech.beshu.ror.es.IndexJsonContentService._

import java.util.Base64
import scala.collection.JavaConverters._

@Singleton
class EsIndexJsonContentService(client: NodeClient,
                                nodeName: String,
                                threadPool: ThreadPool,
                                ignore: Unit) // hack!
  extends IndexJsonContentService
    with Logging {

  @Inject
  def this(client: NodeClient,
           nodeName: String,
           threadPool: ThreadPool) {
    this(client, nodeName, threadPool, ())
  }

  override def sourceOf(index: IndexName.Full,
                        id: String): Task[Either[ReadError, util.Map[String, AnyRef]]] = {
    Task {
      addXpackSecurityHeader()
      client
        .get(
          client
            .prepareGet()
            .setIndex(index.name.value)
            .setId(id)
            .request()
        )
        .actionGet()
    }
      .map { response =>
        Option(response.getSourceAsMap) match {
          case Some(map) =>
            Right(map)
          case None =>
            logger.warn(s"Document [${index.show} ID=$id] _source is not available. Assuming it's empty")
            Right(Maps.newHashMap[String, AnyRef]())
        }
      }
      .executeOn(RorSchedulers.blockingScheduler)
      .onErrorRecover {
        case _: ResourceNotFoundException => Left(ContentNotFound)
        case ex =>
          logger.error(s"Cannot get source of document [${index.show} ID=$id]", ex)
          Left(CannotReachContentSource)
      }
  }

  override def saveContent(index: IndexName.Full,
                           id: String,
                           content: util.Map[String, String]): Task[Either[WriteError, Unit]] = {
    Task {
      addXpackSecurityHeader()
      client
        .index(
          client
            .prepareIndex()
            .setIndex(index.name.value)
            .setId(id)
            .setSource(content, XContentType.JSON)
            .setRefreshPolicy(RefreshPolicy.WAIT_UNTIL)
            .request()
        )
        .actionGet()
    }
      .map { response =>
        response.status().getStatus match {
          case status if status / 100 == 2 =>
            Right(())
          case status =>
            logger.error(s"Cannot write to document [${index.show} ID=$id]. Unexpected response: HTTP $status, response: ${response.toString}")
            Left(CannotWriteToIndex)
        }
      }
      .executeOn(RorSchedulers.blockingScheduler)
      .onErrorRecover {
        case ex =>
          logger.error(s"Cannot write to document [${index.show} ID=$id]", ex)
          Left(CannotWriteToIndex)
      }
  }

  private def getAuthenticationHeaderValue: String = {
    val output = new BytesStreamOutput()
    val currentVersion = Version.CURRENT
    output.setVersion(currentVersion)
    Version.writeVersion(currentVersion, output)
    output.writeBoolean(true)
    output.writeString("_xpack")
    output.writeString(nodeName)
    output.writeString("__attach")
    output.writeString("__attach")
    output.writeBoolean(false)
    if (output.getVersion.onOrAfter(Version.V_6_7_0)) {
      output.writeVInt(4) // Internal
      output.writeMap(Map[String, Object]().asJava)
    }
    Base64.getEncoder.encodeToString(BytesReference.toBytes(output.bytes()))
  }

  private def addXpackSecurityHeader(): Unit = {
    val tc = threadPool.getThreadContext
    Option(tc.getHeader("_xpack_security_authentication")) match {
      case Some(_) =>
      case None => threadPool.getThreadContext.putHeader("_xpack_security_authentication", getAuthenticationHeaderValue)
    }
  }

}
