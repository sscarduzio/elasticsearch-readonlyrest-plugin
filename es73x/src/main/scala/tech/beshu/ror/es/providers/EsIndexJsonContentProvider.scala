package tech.beshu.ror.es.providers

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
import tech.beshu.ror.es.IndexJsonContentManager
import tech.beshu.ror.es.IndexJsonContentManager._

@Singleton
class EsIndexJsonContentProvider(client: NodeClient,
                                 ignore: Unit) // hack!
  extends IndexJsonContentManager
    with Logging {

  @Inject
  def this(client: NodeClient) {
    this(client, ())
  }

  override def sourceOf(index: domain.IndexName,
                        `type`: String,
                        id: String): Task[Either[ReadError, util.Map[String, AnyRef]]] = Task(
    client
      .get(client.prepareGet(index.value.value, `type`, id).request())
      .actionGet()
  )
    .map { response =>
      Option(response.getSourceAsMap) match {
        case Some(map) =>
          Right(map)
        case None =>
          logger.warn(s"Document ${index.show}/${`type`}/$id _source is not available. Assuming it's empty")
          Right(Maps.newHashMap[String, AnyRef]())
      }
    }
    .executeOn(Ror.blockingScheduler)
    .onErrorRecover {
      case _: ResourceNotFoundException => Left(ContentNotFound)
      case ex =>
        logger.error(s"Cannot get source of document ${index.show}/${`type`}/$id", ex)
        Left(CannotReachContentSource)
    }

  override def saveContent(index: domain.IndexName,
                           `type`: String,
                           id: String,
                           content: util.Map[String, String]): Task[Either[WriteError, Unit]] = Task(
    client
      .index(
        client
          .prepareIndex(index.value.value, `type`, id)
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
          logger.error(s"Cannot write to document ${index.show}/${`type`}/$id. Unexpected response: HTTP $status, response: ${response.toString}")
          Left(CannotWriteToIndex)
      }
    }
    .executeOn(Ror.blockingScheduler)
    .onErrorRecover {
      case ex =>
        logger.error(s"Cannot write to document ${index.show}/${`type`}/$id", ex)
        Left(CannotWriteToIndex)
    }
}
