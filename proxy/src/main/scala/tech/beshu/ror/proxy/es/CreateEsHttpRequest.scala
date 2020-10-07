/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import java.util

import com.twitter.finagle.http.{Fields, Method, Request, Version}
import io.lemonlabs.uri.config.UriConfig
import io.lemonlabs.uri.parsing.UrlParser
import io.lemonlabs.uri.{AbsolutePath, RelativeUrl, UrlPath}
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.cookie.{ServerCookieDecoder, ServerCookieEncoder}
import org.elasticsearch.common.bytes.{BytesArray, BytesReference}
import org.elasticsearch.http.{HttpRequest => EsHttpRequest, HttpResponse => EsHttpResponse}
import org.elasticsearch.rest.{RestRequest, RestStatus}

import scala.collection.JavaConverters._
import scala.collection.mutable

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
          url.copy(query = url.query.addParam(UrlParser.parseQueryParam("pretty").get)).toString()
        case _ =>
          request.uri
      }
    }

    private lazy val requestHeaders: Map[String, List[String]] = {
      headers
        .map {
          case (key, values) if key.toLowerCase == Fields.ContentType.toLowerCase =>
            // ES requires case sensitive Content-Type header name
            (Fields.ContentType, values)
          case (key, values) =>
            (key, values)
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
      requestHeaders.mapValues(_.asJava).asJava

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
      val newHeaders = requestHeaders.filterKeys(key => key.toLowerCase != header.toLowerCase)
      from(request, newHeaders)
    }

    override def createResponse(status: RestStatus, content: BytesReference): EsHttpResponse =
      new EsProxyHttpResponse(status, content, mutable.Map.empty)

    override def release(): Unit = ()

    override def releaseAndCopy(): EsHttpRequest = from(request, requestHeaders)

    private def slashOrEmpty(urlPath: UrlPath) = urlPath == UrlPath.slash || urlPath == AbsolutePath(Vector(""))
  }

  private class EsProxyHttpResponse(status: RestStatus,
                                    content: BytesReference,
                                    headers: mutable.Map[String, List[String]])
    extends EsHttpResponse {

    override def addHeader(name: String, value: String): Unit = {
      headers.get(name) match {
        case None => headers.put(name, value :: Nil)
        case Some(values) => headers.put(name, value :: values)
      }
    }

    override def containsHeader(name: String): Boolean = {
      headers.exists { case (headerName, _) => headerName == name}
    }
  }
}
