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
package tech.beshu.ror.unit.acl.factory

import com.sun.net.httpserver.HttpServer
import io.lemonlabs.uri.Url
import monix.execution.Scheduler.Implicits.global
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient.Method
import tech.beshu.ror.accesscontrol.factory.SimpleHttpClient.Config

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}

class ApacheBasedSimpleHttpClientTests extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val nonAsciiJson = """{"groups":["zażółć gęślą jaźń"]}"""

  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  private val clientsFactory = HttpClientsFactory.default()
  private lazy val client = clientsFactory.create(Config.default)

  override protected def beforeAll(): Unit = {
    respond("/json-without-charset", Some("application/json"), nonAsciiJson.getBytes(UTF_8))
    respond(
      "/json-with-charset",
      Some("application/json; charset=ISO-8859-1"),
      "café".getBytes(ISO_8859_1)
    )
    respond("/no-content-type", None, nonAsciiJson.getBytes(UTF_8))
    respond("/empty", Some("application/json"), Array.emptyByteArray)
    server.start()
  }

  override protected def afterAll(): Unit = {
    clientsFactory.shutdown().runSyncUnsafe()
    server.stop(0)
  }

  "An HTTP client" should {
    "decode a body as UTF-8 when the Content-Type has no charset" in {
      get("/json-without-charset") should be(HttpClient.Response(200, nonAsciiJson))
    }
    "decode a body with the charset of the Content-Type" in {
      get("/json-with-charset") should be(HttpClient.Response(200, "café"))
    }
    "decode a body as UTF-8 when there is no Content-Type" in {
      get("/no-content-type") should be(HttpClient.Response(200, nonAsciiJson))
    }
    "return an empty body when the response has no body" in {
      get("/empty") should be(HttpClient.Response(200, ""))
    }
  }

  private def get(path: String): HttpClient.Response = {
    val url = Url.parse(s"http://127.0.0.1:${server.getAddress.getPort}$path")
    client.send(HttpClient.Request(Method.Get, url, Map.empty))(RequestId("test")).runSyncUnsafe()
  }

  private def respond(path: String, contentType: Option[String], body: Array[Byte]): Unit = {
    server.createContext(
      path,
      exchange => {
        contentType.foreach(exchange.getResponseHeaders.set("Content-Type", _))
        // -1 means "no body", 0 means "chunked"
        exchange.sendResponseHeaders(200, if (body.isEmpty) -1 else body.length.toLong)
        if (body.nonEmpty) exchange.getResponseBody.write(body)
        exchange.close()
      }
    )
  }

}
