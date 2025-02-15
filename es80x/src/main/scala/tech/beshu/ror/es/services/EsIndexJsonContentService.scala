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

import cats.implicits.*
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.ResourceNotFoundException
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.common.inject.{Inject, Singleton}
import org.elasticsearch.xcontent.XContentType
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.boot.RorSchedulers
import tech.beshu.ror.es.IndexJsonContentService
import tech.beshu.ror.es.IndexJsonContentService.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.ScalaOps.*

import scala.annotation.unused
import scala.jdk.CollectionConverters.*

@Singleton
class EsIndexJsonContentService(client: NodeClient,
                                @unused constructorDiscriminator: Unit)
  extends IndexJsonContentService
    with Logging {

  @Inject
  def this(client: NodeClient) = {
    this(client, ())
  }

  override def sourceOf(index: IndexName.Full,
                        id: String): Task[Either[ReadError, Map[String, String]]] = {
    Task {
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
        if (response.isExists) {
          Option(response.getSourceAsMap) match {
            case Some(map) =>
              val source = map.asScala.toMap.asStringMap
              logger.debug(s"Document [${index.show} ID=$id] _source: ${showSource(source)}")
              Right(source)
            case None =>
              logger.warn(s"Document [${index.show} ID=$id] _source is not available. Assuming it's empty")
              Right(Map.empty[String, String])
          }
        } else {
          logger.debug(s"Document [${index.show} ID=$id] not exist")
          Left(ContentNotFound)
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
                           content: Map[String, String]): Task[Either[WriteError, Unit]] = {
    Task {
      client
        .index(
          client
            .prepareIndex()
            .setIndex(index.name.value)
            .setId(id)
            .setSource(content.asJava, XContentType.JSON)
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

  private def showSource(source: Map[String, String]) = {
    ujson.write(source)
  }
}
