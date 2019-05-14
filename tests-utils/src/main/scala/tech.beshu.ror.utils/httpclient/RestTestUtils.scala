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
package tech.beshu.ror.utils.httpclient

import java.util.Optional

import com.jayway.jsonpath.JsonPath
import com.mashape.unirest.http.Unirest
import org.junit.Assert.assertEquals
import org.testcontainers.shaded.com.google.common.net.HostAndPort
import tech.beshu.ror.utils.misc.Tuple

class RestTestUtils(restClient: RestClient, endpoint: HostAndPort) {
  val url = restClient.from("").toASCIIString

  Unirest.setHttpClient(restClient.getUnderlyingClient)

  def useCredentials(user: String, pass: String) = Unirest.setHttpClient(
    new RestClient(
      true,
      endpoint.getHostText,
      endpoint.getPort,
      Optional.of(Tuple.from(user, pass))
    ).getUnderlyingClient
  )

  def createIndex(indexName: String) = {
    println("Added empty index: " + Unirest.put(url + indexName)
      .header("refresh", "wait_for")
      .header("timeout", "50s")
      .asString().getBody)
  }

  def writeDocument(indexName: String, documentNameAndContent: String, extraHeaders: Map[String, String] = Map()) = {

    // Create  index with 1 doc in it
    val req = Unirest.put(url + s"${indexName}/documents/${documentNameAndContent}")
      .header("refresh", "wait_for")
      .header("timeout", "50s")
      .header("Content-Type", "application/json")

    extraHeaders.foreach(kv => req.header(kv._1, kv._2))

    println(s"ES DOCUMENT WRITTEN IN ${indexName}! " + req.body(s"""{"id": "${documentNameAndContent}"}""")
      .asString().getBody)
  }

  def msearchRequest(body: String, extraHeaders: Map[String, String] = Map()) = {
    val req = Unirest.post(url + "_msearch")
      .header("Content-Type", "application/x-ndjson")
    extraHeaders.foreach(kv => req.header(kv._1, kv._2))

    println("MSEARCH BODY: " + body)
    val resp = req.body(body).asString()
    println("MSEARCH RESPONSE: " + resp.getBody)
    assertEquals(200, resp.getStatus)

    val jsonPath = JsonPath.parse(resp.getBody)
    val result = jsonPath.read("$.responses[*].hits.total.value").toString
    if(result == "[]") jsonPath.read("$.responses[*].hits.total").toString
    else result
  }

}

