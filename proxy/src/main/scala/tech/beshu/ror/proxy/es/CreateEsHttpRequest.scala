/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import java.util

import com.twitter.finagle.http.{Method, Request, Version}
import io.lemonlabs.uri.config.UriConfig
import io.lemonlabs.uri.{AbsolutePath, RelativeUrl, UrlPath}
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.cookie.{ServerCookieDecoder, ServerCookieEncoder}
import org.elasticsearch.common.bytes.{BytesArray, BytesReference}
import org.elasticsearch.http.{HttpRequest => EsHttpRequest, HttpResponse => EsHttpResponse}
import org.elasticsearch.rest.{RestRequest, RestStatus}

import scala.collection.JavaConverters._

object CreateEsHttpRequest {

  def from(request: Request): EsHttpRequest = from(
    request,
    request
      .headerMap
      .keys
      .map(key => (key, request.headerMap.getAll(key).toList))
      .toMap
  )

  private def from(request: Request,
                   headers: Map[String, List[String]]): EsHttpRequest = new EsHttpRequest {

    private lazy val requestUri = {
      implicit val uriConfig: UriConfig = UriConfig.default
      RelativeUrl.parseOption(request.uri) match {
        case Some(url) if slashOrEmpty(url.path) =>
          // GET / response is rendered as GET /?pretty - proxy wants to reproduce this strange ES behaviour
          url.copy(query = url.query.addParam("pretty")).toString()
        case _ =>
          request.uri
      }
    }

    override def method(): RestRequest.Method = request.method match {
      case Method.Get => RestRequest.Method.GET
      case Method.Post => RestRequest.Method.POST
      case Method.Put => RestRequest.Method.PUT
      case Method.Delete => RestRequest.Method.DELETE
      case Method.Head => RestRequest.Method.HEAD
      case Method.Patch => RestRequest.Method.PATCH
      case Method.Trace => RestRequest.Method.TRACE
      case Method.Connect => RestRequest.Method.CONNECT
      case Method.Options => RestRequest.Method.OPTIONS
      case unsupported => throw new IllegalArgumentException(s"Unsupported http method: $unsupported")
    }

    override def uri(): String = requestUri

    override def content(): BytesReference = new BytesArray(request.contentString.getBytes)

    override def getHeaders: util.Map[String, util.List[String]] =
      headers.mapValues(_.asJava).asJava

    override def strictCookies(): util.List[String] = {
      val cookies = request
        .headerMap
        .getAll(HttpHeaderNames.COOKIE.toString())
        .flatMap { cookieStr =>
          ServerCookieDecoder.STRICT.decode(cookieStr).asScala
        }
        .toList
      if (cookies.nonEmpty) ServerCookieEncoder.STRICT.encode(cookies.asJava)
      else List.empty.asJava
    }

    override def protocolVersion(): EsHttpRequest.HttpVersion = {
      request.version match {
        case Version.Http10 => EsHttpRequest.HttpVersion.HTTP_1_0
        case Version.Http11 => EsHttpRequest.HttpVersion.HTTP_1_1
        case unsupported => throw new IllegalArgumentException(s"Unsupported http protocol version: $unsupported")
      }
    }

    override def removeHeader(header: String): EsHttpRequest = {
      from(request, headers - header)
    }

    override def createResponse(status: RestStatus, content: BytesReference): EsHttpResponse =
      new EsHttpResponse {
        override def addHeader(name: String, value: String): Unit = ()

        override def containsHeader(name: String): Boolean = false
      }

    override def release(): Unit = ()

    override def releaseAndCopy(): EsHttpRequest = from(request, headers)

    private def slashOrEmpty(urlPath: UrlPath) = urlPath == UrlPath.slash || urlPath == AbsolutePath(Vector(""))
  }
}
