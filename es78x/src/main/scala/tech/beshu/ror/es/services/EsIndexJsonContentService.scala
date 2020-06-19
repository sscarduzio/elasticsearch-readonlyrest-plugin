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
import org.elasticsearch.ResourceNotFoundException
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.common.inject.{Inject, Singleton}
import org.elasticsearch.common.xcontent.XContentType
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.boot.Ror
import tech.beshu.ror.es.IndexJsonContentService
import tech.beshu.ror.es.IndexJsonContentService._

@Singleton
class EsIndexJsonContentService(client: NodeClient,
                                ignore: Unit) // hack!
  extends IndexJsonContentService
    with Logging {

  @Inject
  def this(client: NodeClient) {
    this(client, ())
  }

  override def sourceOf(index: domain.IndexName,
                        id: String): Task[Either[ReadError, util.Map[String, AnyRef]]] = {
    Task(
      client
        .get(
          client
            .prepareGet()
            .setIndex(index.value.value)
            .setId(id)
            .request()
        )
        .actionGet())
      .map { response =>
        Option(response.getSourceAsMap) match {
          case Some(map) =>
            Right(map)
          case None =>
            logger.warn(s"Document [${index.show} ID=$id] _source is not available. Assuming it's empty")
            Right(Maps.newHashMap[String, AnyRef]())
        }
      }
      .executeOn(Ror.blockingScheduler)
      .onErrorRecover {
        case _: ResourceNotFoundException => Left(ContentNotFound)
        case ex =>
          logger.error(s"Cannot get source of document [${index.show} ID=$id]", ex)
          Left(CannotReachContentSource)
      }
  }

  override def saveContent(index: domain.IndexName,
                           id: String,
                           content: util.Map[String, String]): Task[Either[WriteError, Unit]] = {
    Task(
      client
        .index(
          client
            .prepareIndex()
            .setIndex(index.value.value)
            .setId(id)
            .setSource(content, XContentType.JSON)
            .setRefreshPolicy(RefreshPolicy.WAIT_UNTIL)
            .request()
        )
        .actionGet()
    )
      .map { response =>
        response.status().getStatus match {
          case status if status / 100 == 2 =>
            Right(())
          case status =>
            logger.error(s"Cannot write to document [${index.show} ID=$id]. Unexpected response: HTTP $status, response: ${response.toString}")
            Left(CannotWriteToIndex)
        }
      }
      .executeOn(Ror.blockingScheduler)
      .onErrorRecover {
        case ex =>
          logger.error(s"Cannot write to document [${index.show} ID=$id]", ex)
          Left(CannotWriteToIndex)
      }
  }
}
