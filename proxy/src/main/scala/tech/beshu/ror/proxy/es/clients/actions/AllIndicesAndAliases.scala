/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.client.{Request, Response}
import org.elasticsearch.common.io.Streams
import tech.beshu.ror.accesscontrol.domain.{FullLocalIndexWithAliases, IndexAttribute, IndexName}
import tech.beshu.ror.proxy.es.exceptions.RorInternalException

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import scala.util.{Failure, Try}

object AllIndicesAndAliases extends Logging {

  def request: Request = new Request("GET", "/_cluster/state")

  implicit class ResponseFromLowLevel(val response: Response) extends AnyVal {
    def toResponse: Try[Set[FullLocalIndexWithAliases]] = {
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

    private def parseResponse: Try[Set[FullLocalIndexWithAliases]] = Try {
      val json = entityJson
      json("indices").obj
        .flatMap { case (index, metadata) =>
          for {
            indexName <- IndexName.Full.fromString(index)
            state = metadata("state").str.toUpperCase match {
              case "CLOSE" => IndexAttribute.Closed
              case _ => IndexAttribute.Opened
            }
            aliases = metadata("aliases").arr.map(_.str).toSet.flatMap(IndexName.Full.fromString)
          } yield FullLocalIndexWithAliases(
            indexName,
            state,
            aliases
          )
        }
        .toSet
    }

    private def entityJson = {
      ujson.read(Streams.copyToString(
        new InputStreamReader(response.getEntity.getContent, StandardCharsets.UTF_8)
      ))
    }
  }
}
