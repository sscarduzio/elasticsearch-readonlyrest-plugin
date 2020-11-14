/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.admin.indices.resolve.ResolveIndexAction
import org.elasticsearch.action.admin.indices.resolve.ResolveIndexAction.{ResolvedAlias, ResolvedDataStream, ResolvedIndex}
import org.elasticsearch.client.{Request, Response}
import org.elasticsearch.common.io.Streams
import org.joor.Reflect.onClass
import tech.beshu.ror.proxy.es.clients.actions.utils.IndicesOptionsOps.toIndicesOptionsOps
import tech.beshu.ror.proxy.es.exceptions.RorInternalException
import tech.beshu.ror.utils.UjsonOps._
import ujson.Value

import scala.collection.JavaConverters._
import scala.util.{Failure, Try}

object ResolveIndex extends Logging {

  implicit class ToLowLevelRequest(val request: ResolveIndexAction.Request) extends AnyVal {
    def toLowLevel: Request = {
      val r = new Request("GET", s"/_resolve/index/${request.indices().mkString(",")}")
      r.addParameters(request.indicesOptions().toQueryParams.asJava)
      r
    }
  }

  implicit class ResponseFromLowLevel(val response: Response) extends AnyVal {
    def toResponse: Try[ResolveIndexAction.Response] = {
      if(response.getStatusLine.getStatusCode == 200) {
        parseResponse
          .recover { case ex =>
            logger.error("Unexpected format of /_resolve/index response", ex)
            throw RorInternalException
          }
      } else {
        logger.error(s"Unexpected status code [${response.getStatusLine.getStatusCode}] returned by /_resolve/index response")
        Failure(RorInternalException)
      }
    }

    private def parseResponse = Try {
      val json = entityJson
      new ResolveIndexAction.Response(
        indicesFrom(json).asJava,
        aliasesFrom(json).asJava,
        dataStreamsFrom(json).asJava
      )
    }

    private def entityJson = {
      ujson.read(Streams.copyToString(
        new InputStreamReader(response.getEntity.getContent, StandardCharsets.UTF_8)
      ))
    }

    private def indicesFrom(json: Value) = {
      json("indices").arr
        .map { index =>
          onClass(classOf[ResolvedIndex])
            .create(
              index("name").str,
              index.opt("aliases").map(_.arr.map(_.str).toList).getOrElse(Nil).toArray,
              index.opt("attributes").map(_.arr.map(_.str).toList).getOrElse(Nil).toArray,
              index.opt("data_stream").map(_.str).orNull
            )
            .get[ResolvedIndex]()
        }
        .toVector
    }

    private def aliasesFrom(json: Value) = {
      json("aliases").arr
        .map { alias =>
          onClass(classOf[ResolvedAlias])
            .create(
              alias("name").str,
              alias.opt("indices").map(_.arr.map(_.str).toList).getOrElse(Nil).toArray,
            )
            .get[ResolvedAlias]()
        }
        .toVector
    }

    private def dataStreamsFrom(json: Value) = {
      json("data_streams").arr
        .map { dataStream =>
          onClass(classOf[ResolvedDataStream])
            .create(
              dataStream("name").str,
              dataStream.opt("backing_indices").map(_.arr.map(_.str).toList).getOrElse(Nil).toArray,
              dataStream("timestamp_field").str
            )
            .get[ResolvedDataStream]()
        }
        .toVector
    }
  }
}
