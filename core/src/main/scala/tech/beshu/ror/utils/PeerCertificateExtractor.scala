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
package tech.beshu.ror.utils

import javax.net.ssl.{SSLEngine, SSLPeerUnverifiedException}
import tech.beshu.ror.accesscontrol.domain.ClientCertificate
import tech.beshu.ror.implicits.*

import java.lang.reflect.{Method, Modifier}
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/** Reads the verified TLS client certificate off the connection a request arrived on.
  *
  * Works whichever component terminated TLS - ROR's own HTTP transport or Elasticsearch with X-Pack
  * Security enabled - because in both cases the certificate lives on the same Netty channel, reachable
  * from the ES `HttpChannel` the request already carries.
  *
  * The walk down to the `SSLEngine` is done reflectively on purpose. The ROR patcher copies ES's
  * `transport-netty4` jar into the plugin directory, so ROR's classloader holds its own copy of
  * `Netty4HttpChannel`; a channel Elasticsearch created (the X-Pack-terminated case) is an instance of
  * ES's copy, and a direct cast would fail. Only public methods are used - no `setAccessible`, which
  * ES 9's entitlements restrict - and everything from the `SSLEngine` onwards is a JDK type, loaded once
  * and therefore identical in every classloader.
  */
object PeerCertificateExtractor extends RequestIdAwareLogging {

  private val sslHandlerClassName = "io.netty.handler.ssl.SslHandler"

  // ROR names its handler, ES/X-Pack names its own; both are checked before falling back to a scan
  private val knownSslHandlerNames = List("ssl", "ssl_netty4_handler")

  private val methodCache = new ConcurrentHashMap[(Class[?], String, List[Class[?]]), Option[Method]]()

  /** @param httpChannel the Elasticsearch `HttpChannel` the request arrived on */
  def clientCertificateOf(httpChannel: AnyRef): Option[ClientCertificate] = {
    peerCertificateOf(httpChannel).flatMap { certificate =>
      ClientCertificate.from(certificate) match {
        case Right(clientCertificate) =>
          Some(clientCertificate)
        case Left(error) =>
          noRequestIdLogger.warn(s"Cannot read the client certificate presented by the caller: ${error.show}")
          None
      }
    }
  }

  private def peerCertificateOf(httpChannel: AnyRef): Option[X509Certificate] =
    sslEngineOf(httpChannel).flatMap(leafCertificateOf)

  private def leafCertificateOf(sslEngine: SSLEngine): Option[X509Certificate] = {
    Try(sslEngine.getSession.getPeerCertificates) match {
      case Success(chain) =>
        chain.headOption.collect { case certificate: X509Certificate => certificate }
      case Failure(_: SSLPeerUnverifiedException) =>
        // the client presented no certificate - the normal case when client authentication is optional
        None
      case Failure(ex) =>
        noRequestIdLogger.debug("Cannot read the peer certificate from the TLS session", ex)
        None
    }
  }

  private[ror] def sslEngineOf(httpChannel: AnyRef): Option[SSLEngine] = {
    for {
      nettyChannel <- step("getNettyChannel", httpChannel)(invoke(_, "getNettyChannel"))
      pipeline <- step("pipeline", nettyChannel)(invoke(_, "pipeline"))
      sslHandler <- step("ssl handler", pipeline)(sslHandlerIn)
      sslEngine <- step("engine", sslHandler)(invoke(_, "engine").collect { case engine: SSLEngine => engine })
    } yield sslEngine
  }

  // Every hop below is reflective, so a mismatch with the Elasticsearch or Netty of the day shows up as
  // a silent None and PKI rules that never match. Saying which hop gave up turns that into one log line.
  private def step[A, B](what: String, from: A)(f: A => Option[B]): Option[B] = {
    val result = f(from)
    if (result.isEmpty) {
      noRequestIdLogger.debug(
        s"Cannot read the TLS client certificate: '${what.show}' is not reachable on ${from.getClass.getName.show}. PKI rules will not match."
      )
    }
    result
  }

  private def sslHandlerIn(pipeline: AnyRef): Option[AnyRef] = {
    def handlerNamed(name: String) = invoke(pipeline, "get", (classOf[String], name))
    knownSslHandlerNames.view
      .flatMap(handlerNamed)
      .headOption
      .orElse {
        // handler names are not part of any contract, so fall back to finding the handler by its type
        handlerNamesIn(pipeline).flatMap(handlerNamed).find(isSslHandler)
      }
  }

  private def handlerNamesIn(pipeline: AnyRef): List[String] = {
    invoke(pipeline, "names")
      .collect { case names: java.util.List[?] => names.asScala.toList.collect { case name: String => name } }
      .getOrElse(List.empty)
  }

  private def isSslHandler(handler: AnyRef): Boolean =
    classHierarchyOf(handler.getClass).exists(_.getName == sslHandlerClassName)

  private def invoke(target: AnyRef, methodName: String, arguments: (Class[?], AnyRef)*): Option[AnyRef] = {
    accessibleMethod(target.getClass, methodName, arguments.map(_._1).toList)
      .flatMap { method =>
        Try(method.invoke(target, arguments.map(_._2)*)) match {
          case Success(result) => Option(result)
          case Failure(ex)     =>
            noRequestIdLogger.debug(
              s"Cannot call '${methodName.show}' on ${target.getClass.getName.show}: ${ex.toString.show}"
            )
            None
        }
      }
  }

  private def accessibleMethod(clazz: Class[?], methodName: String, parameterTypes: List[Class[?]]) = {
    methodCache.computeIfAbsent(
      (clazz, methodName, parameterTypes),
      _ => findAccessibleMethod(clazz, methodName, parameterTypes)
    )
  }

  // the runtime class may not be public (netty channel implementations are, but their pipelines need not be),
  // in which case the method has to be looked up on a public supertype for `invoke` to be allowed
  private def findAccessibleMethod(clazz: Class[?], methodName: String, parameterTypes: List[Class[?]]) = {
    classHierarchyOf(clazz)
      .filter(candidate => Modifier.isPublic(candidate.getModifiers))
      .flatMap(candidate => Try(candidate.getMethod(methodName, parameterTypes*)).toOption)
      .headOption
  }

  private def classHierarchyOf(clazz: Class[?]): LazyList[Class[?]] = {
    if (clazz == null) LazyList.empty
    else clazz #:: (LazyList.from(clazz.getInterfaces) #::: classHierarchyOf(clazz.getSuperclass))
  }

}
