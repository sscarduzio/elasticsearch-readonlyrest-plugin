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
package tech.beshu.ror.utils.elasticsearch

import com.typesafe.scalalogging.LazyLogging
import net.jodah.failsafe.{Failsafe, RetryPolicy}
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpUriRequest
import org.testcontainers.shaded.org.yaml.snakeyaml.{LoaderOptions, Yaml}
import org.testcontainers.shaded.org.yaml.snakeyaml.constructor.SafeConstructor
import tech.beshu.ror.utils.elasticsearch.BaseManager.SimpleResponse
import tech.beshu.ror.utils.httpclient.HttpResponseHelper.stringBodyFrom
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.ScalaUtils._
import ujson.Value

import java.time.Duration
import java.util
import java.util.function.BiPredicate
import scala.collection.JavaConverters._
import scala.util.Try

abstract class BaseManager(client: RestClient) {

  protected def call[T <: SimpleResponse](request: HttpUriRequest, fromResponse: HttpResponse => T): T = {
    client
      .execute {
        additionalHeaders.foldLeft(request) {
          case (req, (name, value)) =>
            req.addHeader(name, value)
            req
        }
      }
      .bracket(fromResponse)
  }

  protected def eventually[T <: SimpleResponse](action: => T)
                                               (until: T => Boolean): T = {
    val policy: RetryPolicy[T] = requestRepeatPolicy[T](shouldRepeat = until andThen(!_))
    Failsafe
      .`with`[T, RetryPolicy[T]](policy)
      .get(() => action)
  }

  private def requestRepeatPolicy[T <: SimpleResponse](shouldRepeat: T => Boolean): RetryPolicy[T] = {
    new RetryPolicy[T]()
      .handleIf(new BiPredicate[T, Throwable] {
        override def test(response: T, ex: Throwable): Boolean = {
          ex != null || Try(shouldRepeat(response)).getOrElse(true)
        }
      })
      .withMaxRetries(20)
      .withDelay(Duration.ofMillis(500))
      .withMaxDuration(Duration.ofSeconds(10))
  }

  protected def additionalHeaders: Map[String, String] = Map.empty

}

object BaseManager {

  type JSON = Value

  final case class SimpleHeader(name: String, value: String)

  class SimpleResponse private[elasticsearch](val response: HttpResponse) {
    val headers: Set[SimpleHeader] = response.getAllHeaders.map(h => SimpleHeader(h.getName, h.getValue)).toSet
    val responseCode: Int = response.getStatusLine.getStatusCode
    val isSuccess: Boolean = responseCode / 100 == 2
    val isForbidden: Boolean = responseCode == 401
    val isNotFound: Boolean = responseCode == 404
    val isBadRequest: Boolean = responseCode == 400
    val body: String = Try(stringBodyFrom(response)).getOrElse("")

    def force(): this.type = {
      if (!isSuccess) throw new IllegalStateException(
        s"Expected success but got HTTP $responseCode, body: ${Try(stringBodyFrom(response)).getOrElse("")}"
      )
      this
    }

    override def toString: String = response.toString
  }

  class JsonResponse(response: HttpResponse) extends SimpleResponse(response) with LazyLogging {
    lazy val responseJson: JSON = ujson.read(body)
  }

  class YamlMapResponse(response: HttpResponse) extends SimpleResponse(response) with LazyLogging {
    val responseYaml: Map[String, Any] = {
      val yamlParser = new Yaml(new SafeConstructor(new LoaderOptions()))
      yamlParser.load[util.LinkedHashMap[String, Object]](body).asScala.toMap
    }
  }
}