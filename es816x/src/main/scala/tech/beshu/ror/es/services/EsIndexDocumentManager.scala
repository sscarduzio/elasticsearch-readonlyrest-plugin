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
import io.circe.Json
import io.circe.parser.*
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.ResourceNotFoundException
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy
import org.elasticsearch.client.internal.node.NodeClient
import org.elasticsearch.index.IndexNotFoundException
import org.elasticsearch.injection.guice.Inject
import org.elasticsearch.xcontent.XContentType
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.boot.RorSchedulers
import tech.beshu.ror.es.IndexDocumentManager
import tech.beshu.ror.es.IndexDocumentManager.*
import tech.beshu.ror.implicits.*

import scala.annotation.unused

class EsIndexDocumentManager(client: NodeClient,
                             @unused constructorDiscriminator: Unit)
  extends IndexDocumentManager
    with Logging {

  @Inject
  def this(client: NodeClient) = {
    this(client, ())
  }

  override def documentAsJson(index: IndexName.Full, id: String): Task[Either[ReadError, Json]] = {
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
          Option(response.getSourceAsString) match {
            case Some(source) =>
              logger.debug(s"Document [${index.show} ID=$id] _source: $source")
              parse(source) match {
                case Right(value) => Right(value)
                case Left(failure) => throw new IllegalStateException(s"Cannot parse document source to JSON: ${failure.toString}")
              }
            case None =>
              logger.warn(s"Document [${index.show} ID=$id] _source is not available. Assuming it's empty")
              Right(Json.Null)
          }
        } else {
          logger.debug(s"Document [${index.show} ID=$id] not exist")
          Left(DocumentNotFound)
        }
      }
      .executeOn(RorSchedulers.blockingScheduler)
      .onErrorRecover {
        case _: IndexNotFoundException => Left(IndexNotFound)
        case _: ResourceNotFoundException => Left(DocumentNotFound)
        case ex =>
          logger.error(s"Cannot get source of document [${index.show} ID=$id]", ex)
          Left(DocumentUnreachable)
      }
  }

  override def saveDocumentJson(index: IndexName.Full, id: String, document: Json): Task[Either[WriteError, Unit]] = {
    Task {
      client
        .index(
          client
            .prepareIndex()
            .setIndex(index.name.value)
            .setId(id)
            .setSource(document.noSpaces, XContentType.JSON)
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
}
