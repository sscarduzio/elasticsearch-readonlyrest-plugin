package tech.beshu.ror.integration

import java.util.Optional

import com.jayway.jsonpath.JsonPath
import com.mashape.unirest.http.Unirest
import org.junit.Assert.assertEquals
import org.testcontainers.shaded.com.google.common.net.HostAndPort
import tech.beshu.ror.utils.Tuple
import tech.beshu.ror.utils.httpclient.RestClient

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
    JsonPath.parse(resp.getBody).read("$.responses[*].hits.total").toString
  }

}

