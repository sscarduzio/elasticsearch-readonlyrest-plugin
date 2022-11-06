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
package tech.beshu.ror.integration.plugin

import java.time.Instant

import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyFreeSpecLike
import tech.beshu.ror.utils.containers.EsClusterProvider
import tech.beshu.ror.utils.elasticsearch._
import tech.beshu.ror.utils.misc.Version

trait DataStreamApiSuite extends AnyFreeSpec
  with BaseSingleNodeEsClusterTest
  with ESVersionSupportForAnyFreeSpecLike
  with BeforeAndAfterEach {
  this: EsClusterProvider =>

  override implicit val rorConfigFileName: String = "/data_stream_api/readonlyrest.yml"

  private lazy val client = clients.head.adminClient
  private lazy val dataStreamManager = new DataStreamManager(client)
  private lazy val templateManager = new IndexTemplateManager(client, esVersion = esVersionUsed)
  private lazy val searchManager = new SearchManager(client)

  private val adminDataStream = dataStreamNameWith("admin")
  private val devDataStream = dataStreamNameWith("dev")
  private val testDataStream = dataStreamNameWith("test")
  private val adminIndexTemplate = indexTemplateNameWith("admin")
  private val devIndexTemplate = indexTemplateNameWith("dev")
  private val testIndexTemplate = indexTemplateNameWith("test")

  private val allIndexTemplates = List(
    adminIndexTemplate,
    devIndexTemplate,
    testIndexTemplate
  )

  "Data stream API" - {
    "Search API" - {
      "without indices rule should" - {
        "allow to search by data stream name" excludeES(allEs6x, allEs7xBelowEs79x) in {
          createDataStreamWithSuccess(adminIndexTemplate, adminDataStream)
          createDocsInDataStream(adminDataStream, 10)
          waitForReindexing()
          searchAllowed(searchManager, adminDataStream, 10)
        }
        "allow to search by data stream name with wildcard" excludeES(allEs6x, allEs7xBelowEs79x) in {
          val createIndexTemplateResponse = templateManager.createTemplate(adminIndexTemplate, indexTemplate(adminDataStream))
          createIndexTemplateResponse.responseCode should be(200)

          List.range(0, 20).foreach { x =>
            val ds = s"$adminDataStream-$x"
            val createDataStreamResponse = dataStreamManager.createDataStream(ds)
            createDataStreamResponse.responseCode should be(200)
            createDocsInDataStream(ds, 1)
          }

          waitForReindexing()

          searchAllowed(searchManager, "data-stream*", 20)
          searchAllowed(searchManager, s"$adminDataStream*", 20)
          searchAllowed(searchManager, s"$adminDataStream-1*", 11) // 1 and 10-19
        }
        "allow to search by data stream index name" excludeES(allEs6x, allEs7xBelowEs79x) in {
          createDataStreamWithSuccess(adminIndexTemplate, adminDataStream)
          createDocsInDataStream(adminDataStream, 10)
          dataStreamManager.rollover(adminDataStream)
          createDocsInDataStream(adminDataStream, 10)
          dataStreamManager.rollover(adminDataStream)
          createDocsInDataStream(adminDataStream, 10)
          waitForReindexing()

          val allDataStreamsResponse = dataStreamManager.getAllDataStreams()
          allDataStreamsResponse.responseCode should be(200)
          val indicesNames = allDataStreamsResponse.responseJson("data_streams").arr.head("indices").arr.map(v => v("index_name").str)
          indicesNames.foreach { indexName =>
            searchAllowed(searchManager, indexName, 10)
          }
        }
        "allow to search by data stream index with wildcard" excludeES(allEs6x, allEs7xBelowEs79x) in {
          createDataStreamWithSuccess(adminIndexTemplate, adminDataStream)
          createDocsInDataStream(adminDataStream, 10)
          dataStreamManager.rollover(adminDataStream)
          createDocsInDataStream(adminDataStream, 10)
          dataStreamManager.rollover(adminDataStream)
          createDocsInDataStream(adminDataStream, 10)
          waitForReindexing()

          searchAllowed(searchManager, ".ds-data-stream*", 30)
          searchAllowed(searchManager, s".ds-$adminDataStream*", 30)
        }
      }
      "with indices rule" - {
        "allow to search by data stream name" excludeES(allEs6x, allEs7xBelowEs79x) in {
          List(
            (adminIndexTemplate, adminDataStream, 30),
            (devIndexTemplate, devDataStream, 10),
            (testIndexTemplate, testDataStream, 5)
          )
            .foreach { case (indexTemplate, dataStream, docsCount) =>
              createDataStreamWithSuccess(indexTemplate, dataStream)
              createDocsInDataStream(dataStream, docsCount)
            }

          waitForReindexing()

          val searchManager1 = new SearchManager(clients.head.basicAuthClient("user1", "pass"))
          searchForbidden(searchManager1, devDataStream)
          searchAllowed(searchManager1, testDataStream, expectedHits = 5)
          searchForbidden(searchManager1, adminDataStream)

          val searchManager = new SearchManager(clients.head.basicAuthClient("user2", "pass"))
          searchAllowed(searchManager, devDataStream, expectedHits = 10)
          searchAllowed(searchManager, testDataStream, expectedHits = 5)
          searchForbidden(searchManager, adminDataStream)
        }
        "allow to search by data stream index with wildcard" excludeES(allEs6x, allEs7xBelowEs79x) in {
          List(
            (adminIndexTemplate, adminDataStream, 30),
            (devIndexTemplate, devDataStream, 10),
            (testIndexTemplate, testDataStream, 5)
          )
            .foreach { case (indexTemplate, dataStream, docsCount) =>
              createDataStreamWithSuccess(indexTemplate, dataStream)
              createDocsInDataStream(dataStream, docsCount)
            }

          waitForReindexing()

          val searchManager = new SearchManager(clients.head.basicAuthClient("user3", "pass"))
          searchAllowed(searchManager, s".ds-$devDataStream*", expectedHits = 10)
          searchAllowed(searchManager, s".ds-$testDataStream*", expectedHits = 5)
          searchAllowed(searchManager, s".ds-$adminDataStream*", expectedHits = 0)
        }
        "allow to search by data stream index" excludeES(allEs6x, allEs7xBelowEs79x) in {
          List(
            (adminIndexTemplate, adminDataStream, 30),
            (devIndexTemplate, devDataStream, 10),
            (testIndexTemplate, testDataStream, 5)
          )
            .foreach { case (indexTemplate, dataStream, docsCount) =>
              createDataStreamWithSuccess(indexTemplate, dataStream)
              createDocsInDataStream(dataStream, docsCount)
            }

          waitForReindexing()

          val searchManager = new SearchManager(clients.head.basicAuthClient("user3", "pass"))
          val allDataStreamsResponse = dataStreamManager.getAllDataStreams()
          allDataStreamsResponse.responseCode should be(200)
          val dataStreamsJson = allDataStreamsResponse.responseJson("data_streams").arr

          def findIndicesForDataStream(name: String) =
            dataStreamsJson
              .find(v => v("name").str == name)
              .map(v => v("indices").arr.map(v => v("index_name").str))

          findIndicesForDataStream(adminDataStream).head.foreach { indexName =>
            searchForbidden(searchManager, indexName)
          }
          findIndicesForDataStream(devDataStream).head.foreach { indexName =>
            searchAllowed(searchManager, indexName, 10)
          }
          findIndicesForDataStream(testDataStream).head.foreach { indexName =>
            searchAllowed(searchManager, indexName, 5)
          }
        }
      }
    }
    "should allow to create data stream" excludeES(allEs6x, allEs7xBelowEs79x) in {
      createDataStreamWithSuccess(adminIndexTemplate, adminDataStream)
    }
    "should allow to add documents to data stream" excludeES(allEs6x, allEs7xBelowEs79x) in {
      createDataStreamWithSuccess(adminIndexTemplate, adminDataStream)
      createDocsInDataStream(adminDataStream, 15)
    }
    "should allow to get all data streams" excludeES(allEs6x, allEs7xBelowEs79x) in {
      createDataStreamWithSuccess(adminIndexTemplate, adminDataStream)
      val response = dataStreamManager.getAllDataStreams()
      response.responseCode should be(200)
      response.responseJson("data_streams").arr.map(v => v("name").str).toList should be(List(adminDataStream))
    }
    "should allow to get data stream" excludeES(allEs6x, allEs7xBelowEs79x) in {
      createDataStreamWithSuccess(adminIndexTemplate, adminDataStream)
      val response = dataStreamManager.getDataStream(adminDataStream)
      response.responseCode should be(200)
      response.responseJson("data_streams").arr.map(v => v("name").str).toList should be(List(adminDataStream))
    }
    "should allow to get data stream stats" excludeES(allEs6x, allEs7xBelowEs79x) in {
      createDataStreamWithSuccess(adminIndexTemplate, adminDataStream)
      val statsResponse = dataStreamManager.getDataStreamStats(adminDataStream)
      statsResponse.responseCode should be(200)
      statsResponse.responseJson("data_stream_count").num.toInt should be(1)
      statsResponse.responseJson("backing_indices").num.toInt should be(1)
    }
    "should allow to rollover data stream" excludeES(allEs6x, allEs7xBelowEs79x) in {
      createDataStreamWithSuccess(adminIndexTemplate, adminDataStream)

      List.range(0, 2).foreach { _ =>
        createDocsInDataStream(adminDataStream, 15)
        dataStreamManager.rollover(adminDataStream).force()
      }

      waitForReindexing()
      val statsResponse = dataStreamManager.getDataStreamStats(adminDataStream)
      statsResponse.responseCode should be(200)
      statsResponse.responseJson("data_stream_count").num.toInt should be(1)
      statsResponse.responseJson("backing_indices").num.toInt should be(3)
    }
    "should allow to migrate index alias to data stream" excludeES(allEs6x, allEs7xBelowEs711x) in {
      val documentManager = new DocumentManager(client, esVersionUsed)
      val indexManager = new IndexManager(client, esVersionUsed)
      documentManager.createDoc("logs-0001", 1, ujson.read(s"""{ "message":"test1", "@timestamp": "${format(Instant.now())}"}""")).force()
      documentManager.createDoc("logs-0001", 2, ujson.read(s"""{ "message":"test2", "@timestamp": "${format(Instant.now())}"}""")).force()
      documentManager.createDoc("logs-0002", 1, ujson.read(s"""{ "message":"test3", "@timestamp": "${format(Instant.now())}"}""")).force()
      indexManager.createAliasOf("logs-0001", "all-logs")

      val createIndexTemplateResponse = templateManager.createTemplate("logs-template-name", indexTemplate("all-logs"))
      createIndexTemplateResponse.responseCode should be(200)

      val migrateToDataStreamResponse = dataStreamManager.migrateToDataStream("all-logs")
      migrateToDataStreamResponse.responseCode should be(200)

      waitForReindexing()
      val statsResponse = dataStreamManager.getDataStreamStats("all-logs")
      statsResponse.responseCode should be(200)
      statsResponse.responseJson("data_stream_count").num.toInt should be(1)
      statsResponse.responseJson("backing_indices").num.toInt should be(1)
    }
    "should allow to modify data stream" excludeES(allEs6x, allEs7xBelowEs716x) in {
      createDataStreamWithSuccess(adminIndexTemplate, adminDataStream)

      List.range(0, 2).foreach { _ =>
        createDocsInDataStream(adminDataStream, 15)
        dataStreamManager.rollover(adminDataStream).force()
      }

      waitForReindexing()

      val getDataStreamResponse = dataStreamManager.getDataStream(adminDataStream)
      getDataStreamResponse.responseCode should be(200)
      val dsIndices = getDataStreamResponse.responseJson("data_streams").arr.head("indices").arr.map(_ ("index_name").str).toList
      dsIndices.size should be(3)

      val modifyResponse = dataStreamManager.modifyDataStreams(ujson.read(
        s"""
           |{
           |  "actions": [
           |    {
           |      "remove_backing_index": {
           |        "data_stream": "$adminDataStream",
           |        "index": "${dsIndices.head}"
           |      }
           |    }
           |  ]
           |}
           |""".stripMargin))
      modifyResponse.responseCode should be(200)

      val getDataStreamResponseAfterModification = dataStreamManager.getDataStream(adminDataStream)
      getDataStreamResponseAfterModification.responseCode should be(200)
      val dsIndicesAfterModification = getDataStreamResponseAfterModification
        .responseJson("data_streams").arr.head("indices").arr.map(_ ("index_name").str).toList
      dsIndicesAfterModification should be(dsIndices.tail)
    }
  }

  private def searchAllowed(searchManager: SearchManager, name: String, expectedHits: Int) = {
    val response = searchManager.search(name)
    response.responseCode should be(200)
    response.responseJson("hits")("total")("value").num.toInt should be(expectedHits)
  }

  private def searchForbidden(searchManager: SearchManager, name: String) = {
    val response = searchAll(searchManager, name)
    response.responseCode should be(401)
  }

  private def searchAll(searchManager: SearchManager,
                        name: String) = {
    val queryAll = ujson.read(
      s"""{
         |  "query": {
         |    "match_all": {}
         |  }
         |}""".stripMargin
    )
    searchManager.search(name, queryAll)
  }

  private def createDataStreamWithSuccess(indexTemplateName: String, dataStreamName: String): Unit = {
    val createIndexTemplateResponse = templateManager.createTemplate(indexTemplateName, indexTemplate(dataStreamName))
    createIndexTemplateResponse.responseCode should be(200)
    val createDataStreamResponse = dataStreamManager.createDataStream(dataStreamName)
    createDataStreamResponse.responseCode should be(200)
  }

  private def createDocsInDataStream(streamName: String, count: Int): Unit = {
    List.range(0, count).foreach { c =>
      val doc = ujson.read(s"""{ "message":"test$c", "@timestamp": "${format(Instant.now())}"}""")
      val response = dataStreamManager.addDocument(streamName, doc)
      response.responseCode should be(201)
    }
  }

  private def waitForReindexing(): Unit = {
    Thread.sleep(1000)
  }

  private def indexTemplate(dataStreamName: String = adminDataStream) = ujson.read(
    s"""
       |{
       |  "index_patterns": ["$dataStreamName*"],
       |  "data_stream": { },
       |  "priority": 500,
       |  "template": {
       |    "mappings": {
       |      "properties": {
       |        "@timestamp": {
       |          "type": "date",
       |          "format": "date_optional_time||epoch_millis"
       |        },
       |        "message": {
       |          "type": "wildcard"
       |        }
       |      }
       |    }
       |  }
       |}
       |""".stripMargin
  )

  private def format(instant: Instant) = instant.toString

  private def indexTemplateNameWith(suffix: String) = s"index-template-$suffix"

  private def dataStreamNameWith(suffix: String) = s"data-stream-$suffix"

  override def beforeEach(): Unit = {
    if (Version.greaterOrEqualThan(esVersionUsed, 7, 9, 0)) {
      val dataStreamsResponse = dataStreamManager.getAllDataStreams().force()
      dataStreamsResponse.responseJson("data_streams").arr.foreach { entry =>
        val response = dataStreamManager.deleteDataStream(entry("name").str)
        response.responseCode should be(200)
      }
      allIndexTemplates.foreach { indexTemplate =>
        templateManager.deleteTemplate(indexTemplate)
      }
    }
    super.beforeEach()
  }

}
