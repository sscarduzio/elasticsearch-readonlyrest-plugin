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
package tech.beshu.ror.integration.suites

import monix.execution.atomic.Atomic
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpec
import tech.beshu.ror.integration.suites.DataStreamApiSuite.{DataStreamNameGenerator, IndexTemplateNameGenerator}
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyFreeSpecLike
import tech.beshu.ror.utils.containers.EsClusterProvider
import tech.beshu.ror.utils.elasticsearch._
import tech.beshu.ror.utils.misc.Version

import java.time.Instant
import scala.util.Random

trait DataStreamApiSuite extends AnyFreeSpec
  with BaseSingleNodeEsClusterTest
  with ESVersionSupportForAnyFreeSpecLike
  with BeforeAndAfterEach {
  this: EsClusterProvider =>

  override implicit val rorConfigFileName: String = "/data_stream_api/readonlyrest.yml"

  private lazy val client = clients.head.adminClient
  private lazy val user1Client = clients.head.basicAuthClient("user1", "pass")
  private lazy val user2Client = clients.head.basicAuthClient("user2", "pass")
  private lazy val user3Client = clients.head.basicAuthClient("user3", "pass")
  private lazy val user4Client = clients.head.basicAuthClient("user4", "pass")
  private lazy val user5Client = clients.head.basicAuthClient("user5", "pass")
  private lazy val user6Client = clients.head.basicAuthClient("user6", "pass")
  private lazy val adminDocumentManager = new DocumentManager(client, esVersionUsed)
  private lazy val adminDataStreamManager = new DataStreamManager(client)
  private lazy val adminIndexManager = new IndexManager(client, esVersionUsed)
  private lazy val adminSearchManager = new SearchManager(client)
  private lazy val adminTemplateManager = new IndexTemplateManager(client, esVersionUsed)

  private val adminDataStream = DataStreamNameGenerator.next("admin")
  private val devDataStream = DataStreamNameGenerator.next("dev")
  private val testDataStream = DataStreamNameGenerator.next("test")

  "Data stream API" - {
    "Search API" - {
      "without rules should" - {
        "allow to search by data stream name" excludeES(allEs6x, allEs7xBelowEs79x) in {
          createDataStream(adminDataStream, IndexTemplateNameGenerator.next)
          createDocsInDataStream(adminDataStream, 2)

          val searchResponse = adminSearchManager.search(adminDataStream)
          searchResponse.totalHits should be(2)
        }
        "allow to search by data stream name with wildcard" excludeES(allEs6x, allEs7xBelowEs79x) in {
          adminTemplateManager.createTemplate(IndexTemplateNameGenerator.next, indexTemplate(adminDataStream)).force()

          List(
            s"$adminDataStream-x0",
            s"$adminDataStream-x1",
            s"$adminDataStream-x2",
            s"$adminDataStream-x10",
            s"$adminDataStream-x11",
          ).foreach { dataStream =>
            adminDataStreamManager.createDataStream(dataStream).force()
            createDocsInDataStream(dataStream, 1)
          }

          List(
            ("data-stream*", 5),
            (s"$adminDataStream*", 5),
            (s"$adminDataStream-x1*", 3),
          )
            .foreach { case (dataStream, expectedHits) =>
              val response = adminSearchManager.searchAll(dataStream)
              response.totalHits should be(expectedHits)
            }
        }
        "allow to search by data stream index name" excludeES(allEs6x, allEs7xBelowEs79x) in {
          createDataStream(adminDataStream, IndexTemplateNameGenerator.next)
          createDocsInDataStream(adminDataStream, 1)
          adminIndexManager.rollover(adminDataStream).force()
          createDocsInDataStream(adminDataStream, 1)
          adminIndexManager.rollover(adminDataStream).force()
          createDocsInDataStream(adminDataStream, 1)

          val allDataStreamsResponse = adminDataStreamManager.getAllDataStreams().force()
          val indicesNames = allDataStreamsResponse.responseJson("data_streams").arr.head("indices").arr.map(v => v("index_name").str)
          indicesNames.foreach { indexName =>
            val response = adminSearchManager.searchAll(indexName)
            response.totalHits should be(1)
          }
        }
        "allow to search by data stream index with wildcard" excludeES(allEs6x, allEs7xBelowEs79x) in {
          createDataStream(adminDataStream, IndexTemplateNameGenerator.next)
          createDocsInDataStream(adminDataStream, 1)
          adminIndexManager.rollover(adminDataStream)
          createDocsInDataStream(adminDataStream, 1)
          adminIndexManager.rollover(adminDataStream)
          createDocsInDataStream(adminDataStream, 1)

          List(
            (".ds-data-stream*", 3),
            (s".ds-$adminDataStream*", 3),
          )
            .foreach { case (dataStream, expectedHits) =>
              val response = adminSearchManager.searchAll(dataStream)
              response.totalHits should be(expectedHits)
            }
        }
      }
      "with indices rule should" - {
        "allow to search by data stream index with wildcard" excludeES(allEs6x, allEs7xBelowEs79x) in {
          List(
            (adminDataStream, 4),
            (devDataStream, 2),
            (testDataStream, 1)
          )
            .foreach { case (dataStream, docsCount) =>
              createDataStream(dataStream, IndexTemplateNameGenerator.next)
              createDocsInDataStream(dataStream, docsCount)
            }

          val searchManager = new SearchManager(user3Client)

          List(
            (s".ds-$devDataStream*", 2),
            (s".ds-$testDataStream*", 1),
            (s".ds-$adminDataStream*", 0),
          )
            .foreach { case (dataStream, expectedHits) =>
              val response = searchManager.searchAll(dataStream)
              response.totalHits should be(expectedHits)
            }
        }
        "allow to search by data stream index" excludeES(allEs6x, allEs7xBelowEs79x) in {
          List(
            (adminDataStream, 4),
            (devDataStream, 2),
            (testDataStream, 1)
          )
            .foreach { case (dataStream, docsCount) =>
              createDataStream(dataStream, IndexTemplateNameGenerator.next)
              createDocsInDataStream(dataStream, docsCount)
            }

          val searchManager = new SearchManager(user3Client)
          val dataStreamsJson = adminDataStreamManager.getAllDataStreams().force().responseJson("data_streams").arr

          def findIndicesForDataStream(name: String) =
            dataStreamsJson
              .find(v => v("name").str == name)
              .map(v => v("indices").arr.map(v => v("index_name").str))

          findIndicesForDataStream(adminDataStream).head.foreach { indexName =>
            val response = searchManager.searchAll(indexName)
            response.responseCode should be(401)
          }
          findIndicesForDataStream(devDataStream).head.foreach { indexName =>
            val response = searchManager.searchAll(indexName)
            response.totalHits should be(2)
          }
          findIndicesForDataStream(testDataStream).head.foreach { indexName =>
            val response = searchManager.searchAll(indexName)
            response.totalHits should be(1)
          }
        }
      }
      "with data_streams rule should" ignore { // todo enable when search request will be aware of data streams in ACL
        "allow to search by data stream name" excludeES(allEs6x, allEs7xBelowEs79x) in {
          List(
            (adminDataStream, 4),
            (devDataStream, 2),
            (testDataStream, 1)
          )
            .foreach { case (dataStream, docsCount) =>
              createDataStream(dataStream, IndexTemplateNameGenerator.next)
              createDocsInDataStream(dataStream, docsCount)
            }

          val searchManager1 = new SearchManager(user1Client)

          List(devDataStream, adminDataStream).foreach { dataStream =>
            val response = searchManager1.searchAll(dataStream)
            response.responseCode should be(401)
          }

          val sm1TestDataStreamResponse = searchManager1.searchAll(testDataStream)
          sm1TestDataStreamResponse.totalHits should be(1)

          val searchManager2 = new SearchManager(user6Client)
          val sm2Response1 = searchManager2.searchAll(devDataStream)
          sm2Response1.totalHits should be(2)
          val sm2Response2 = searchManager2.searchAll(testDataStream)
          sm2Response2.totalHits should be(1)
          val sm2Response3 = searchManager2.searchAll(adminDataStream)
          sm2Response3.responseCode should be(401)
        }
        "allow to search by data stream name with wildcard" excludeES(allEs6x, allEs7xBelowEs79x) in {
          adminTemplateManager.createTemplate(IndexTemplateNameGenerator.next, indexTemplate(adminDataStream)).force()
          adminTemplateManager.createTemplate(IndexTemplateNameGenerator.next, indexTemplate(testDataStream)).force()

          List(
            s"$adminDataStream-x0",
            s"$testDataStream-x1",
            s"$testDataStream-x2",
            s"$adminDataStream-x10",
            s"$testDataStream-x11",
          ).foreach { dataStream =>
            adminDataStreamManager.createDataStream(dataStream).force()
            createDocsInDataStream(dataStream, 1)
          }

          val searchManager = new SearchManager(user1Client)

          val response1 = searchManager.searchAll("data-stream*")
          response1.totalHits should be(3)
          val response2 = searchManager.searchAll(s"$adminDataStream*")
          response2.responseCode should be(401)
          val response3 = searchManager.searchAll(s"$testDataStream-x1*")
          response3.totalHits should be(2)
        }
      }
    }
    "create data stream" - {
      "without data_streams rule should" - {
        "allow to create data stream" excludeES(allEs6x, allEs7xBelowEs79x) in {
          val dataStream = adminDataStream
          createDataStream(dataStream, IndexTemplateNameGenerator.next)
        }
      }
      "with data_streams rule should" - {
        "allow to create data stream when" - {
          "the data stream name does match the allowed data stream names when" - {
            "exact data stream matching" excludeES(allEs6x, allEs7xBelowEs79x) in {
              val dataStream = "data-stream-prod"
              adminTemplateManager.createTemplate(IndexTemplateNameGenerator.next, indexTemplate(dataStream)).force()

              val dsm = new DataStreamManager(user2Client)
              val response = dsm.createDataStream(dataStream)
              response.responseCode should be(200)
            }
            "wildcard data stream matching" excludeES(allEs6x, allEs7xBelowEs79x) in {
              val dataStream = DataStreamNameGenerator.next("test")
              adminTemplateManager.createTemplate(IndexTemplateNameGenerator.next, indexTemplate(dataStream)).force()

              val dsm = new DataStreamManager(user1Client)
              val response = dsm.createDataStream(dataStream)
              response.responseCode should be(200)
            }
          }
        }
        "forbid to create data stream when" - {
          "the data stream name does not match the allowed data stream names" excludeES(allEs6x, allEs7xBelowEs79x) in {
            val dataStream = DataStreamNameGenerator.next("admin")
            adminTemplateManager.createTemplate(IndexTemplateNameGenerator.next, indexTemplate(dataStream)).force()

            val dsm = new DataStreamManager(user2Client)
            val response = dsm.createDataStream(dataStream)
            response.responseCode should be(401)
          }
        }
      }
    }
    "allow to add documents to data stream" excludeES(allEs6x, allEs7xBelowEs79x) in {
      val dataStream = DataStreamNameGenerator.next("admin")
      createDataStream(dataStream, IndexTemplateNameGenerator.next)
      createDocsInDataStream(dataStream, 1)
    }
    "get data stream" - {
      "get all data streams" - {
        "without data_streams rule should" - {
          "allow to get all data streams" excludeES(allEs6x, allEs7xBelowEs79x) in {
            val dataStream1 = DataStreamNameGenerator.next("admin")
            val dataStream2 = DataStreamNameGenerator.next("dev")

            createDataStream(dataStream1, IndexTemplateNameGenerator.next)
            createDataStream(dataStream2, IndexTemplateNameGenerator.next)

            val response = adminDataStreamManager.getAllDataStreams()
            response.responseCode should be(200)
            response.responseJson("data_streams").arr.map(v => v("name").str).toSet should be(Set(dataStream1, dataStream2))
          }
        }
        "with data_streams rule should" - {
          "allow to get only allowed data streams when" - {
            "exact data stream matching" excludeES(allEs6x, allEs7xBelowEs79x) in {
              val dataStream = "data-stream-prod"
              createDataStream(dataStream, IndexTemplateNameGenerator.next)
              createDataStream(DataStreamNameGenerator.next("dev"), IndexTemplateNameGenerator.next)

              val dsm = new DataStreamManager(user2Client)
              val response = dsm.getAllDataStreams()
              response.responseCode should be(200)
              response.responseJson("data_streams").arr.map(v => v("name").str).toSet should be(Set(dataStream))
            }
            "wildcard data stream matching" excludeES(allEs6x, allEs7xBelowEs79x) in {
              val dataStream1 = DataStreamNameGenerator.next("test")
              val dataStream2 = DataStreamNameGenerator.next("test")
              createDataStream(dataStream1, IndexTemplateNameGenerator.next)
              createDataStream(dataStream2, IndexTemplateNameGenerator.next)
              createDataStream("data-stream-prod", IndexTemplateNameGenerator.next)
              createDataStream(DataStreamNameGenerator.next("dev"), IndexTemplateNameGenerator.next)

              val dsm = new DataStreamManager(user1Client)
              val response = dsm.getAllDataStreams()
              response.responseCode should be(200)
              response.responseJson("data_streams").arr.map(v => v("name").str).toSet should be(Set(dataStream1, dataStream2))
            }
          }
        }
        "with indices rule should" - {
          "allow to get all data streams when" - {
            "the user has access to all indices" excludeES(allEs6x, allEs7xBelowEs79x) in {
              val dataStream1 = DataStreamNameGenerator.next("admin")
              val dataStream2 = DataStreamNameGenerator.next("dev")

              createDataStream(dataStream1, IndexTemplateNameGenerator.next)
              createDataStream(dataStream2, IndexTemplateNameGenerator.next)

              val dsm = new DataStreamManager(user4Client)
              val response = dsm.getAllDataStreams()
              response.responseCode should be(200)
              response.responseJson("data_streams").arr.map(v => v("name").str).toSet should be(Set(dataStream1, dataStream2))
            }
            "user has access to all backing indices" excludeES(allEs6x, allEs7xBelowEs79x) in {
              val dataStream1 = DataStreamNameGenerator.next("admin")
              val dataStream2 = DataStreamNameGenerator.next("dev")

              createDataStream(dataStream1, IndexTemplateNameGenerator.next)
              createDataStream(dataStream2, IndexTemplateNameGenerator.next)

              val dsm = new DataStreamManager(user4Client)
              val response = dsm.getAllDataStreams()
              response.responseCode should be(200)
              response.responseJson("data_streams").arr.map(v => v("name").str).toSet should be(Set(dataStream1, dataStream2))
            }
            "user has access to certain backing indices" excludeES(allEs6x, allEs7xBelowEs79x) in {
              val dataStream1 = DataStreamNameGenerator.next("dev")
              val dataStream2 = DataStreamNameGenerator.next("test")
              val dataStream3 = DataStreamNameGenerator.next("admin")

              createDataStream(dataStream1, IndexTemplateNameGenerator.next)
              createDataStream(dataStream2, IndexTemplateNameGenerator.next)
              createDataStream(dataStream3, IndexTemplateNameGenerator.next)

              val dsm = new DataStreamManager(user3Client)
              val response = dsm.getAllDataStreams()
              response.responseCode should be(200)
              response.responseJson("data_streams").arr.map(v => v("name").str).toSet should be(Set(dataStream1, dataStream2))
            }
          }
          "return empty list of data streams when" - {
            "the user has no access to any backing indices" excludeES(allEs6x, allEs7xBelowEs79x) in {
              val dataStream = DataStreamNameGenerator.next("admin")
              createDataStream(dataStream, IndexTemplateNameGenerator.next)

              val dsm = new DataStreamManager(user1Client)
              val response = dsm.getAllDataStreams()
              response.responseCode should be(200)
              response.responseJson("data_streams").arr.map(v => v("name").str).toSet should be(Set.empty)
            }
          }
        }
      }
      "get single data stream" - {
        "without data_streams rule should" - {
          "allow to get data stream" excludeES(allEs6x, allEs7xBelowEs79x) in {
            val dataStream1 = DataStreamNameGenerator.next("admin")
            val dataStream2 = DataStreamNameGenerator.next("dev")

            createDataStream(dataStream1, IndexTemplateNameGenerator.next)
            createDataStream(dataStream2, IndexTemplateNameGenerator.next)

            val response = adminDataStreamManager.getAllDataStreams()
            response.responseCode should be(200)
            response.responseJson("data_streams").arr.map(v => v("name").str).toSet should be(Set(dataStream1, dataStream2))
          }
        }
        "with data_streams rule should" - {
          "exact data stream matching" excludeES(allEs6x, allEs7xBelowEs79x) in {
            val dataStream = "data-stream-prod"
            createDataStream(dataStream, IndexTemplateNameGenerator.next)
            createDataStream(DataStreamNameGenerator.next("dev"), IndexTemplateNameGenerator.next)

            val dsm = new DataStreamManager(user2Client)
            val response = dsm.getDataStream(dataStream)
            response.responseCode should be(200)
            response.responseJson("data_streams").arr.map(v => v("name").str).toSet should be(Set(dataStream))
          }
          "wildcard data stream matching" excludeES(allEs6x, allEs7xBelowEs79x) in {
            val dataStream1 = DataStreamNameGenerator.next("test")
            val dataStream2 = DataStreamNameGenerator.next("test")
            createDataStream(dataStream1, IndexTemplateNameGenerator.next)
            createDataStream(dataStream2, IndexTemplateNameGenerator.next)
            createDataStream("data-stream-prod", IndexTemplateNameGenerator.next)
            createDataStream(DataStreamNameGenerator.next("dev"), IndexTemplateNameGenerator.next)

            val dsm = new DataStreamManager(user1Client)
            val response = dsm.getDataStream(dataStream1)
            response.responseCode should be(200)
            response.responseJson("data_streams").arr.map(v => v("name").str).toSet should be(Set(dataStream1))
          }
        }
        "with indices rule should" - {
          "allow to get data stream when" - {
            "the data stream name does match the allowed data stream names" excludeES(allEs6x, allEs7xBelowEs79x) in {
              val dataStream = DataStreamNameGenerator.next("test")
              createDataStream(dataStream, IndexTemplateNameGenerator.next)

              val dsm = new DataStreamManager(user3Client)
              val response = dsm.getDataStream(dataStream)
              response.responseCode should be(200)
              response.responseJson("data_streams").arr.map(v => v("name").str).toSet should be(Set(dataStream))
            }
          }
          "return empty data streams when" - {
            "the data stream name does not match the allowed data stream names" excludeES(allEs6x, allEs7xBelowEs79x) in {
              val dataStream = DataStreamNameGenerator.next("admin")
              createDataStream(dataStream, IndexTemplateNameGenerator.next)

              val dsm = new DataStreamManager(user3Client)
              val response = dsm.getDataStream(dataStream)
              response.responseCode should be(200)
              response.responseJson("data_streams").arr.map(v => v("name").str).toSet should be(Set.empty)
            }
          }
        }
      }
    }
    "get data stream stats" - {
      "without data_streams rule should" - {
        "allow to get data stream stats" excludeES(allEs6x, allEs7xBelowEs79x) in {
          val dataStream1 = DataStreamNameGenerator.next("admin")
          val dataStream2 = DataStreamNameGenerator.next("admin")
          createDataStream(dataStream1, IndexTemplateNameGenerator.next)
          createDocsInDataStream(dataStream1, 1)
          createDataStream(dataStream2, IndexTemplateNameGenerator.next)
          createDocsInDataStream(dataStream2, 2)
          val response = adminDataStreamManager.getDataStreamStats("*")
          response.responseCode should be(200)
          response.responseJson("data_stream_count").num.toInt should be(2)
          response.responseJson("backing_indices").num.toInt should be(2)
        }
      }
      "with data_streams rule should" - {
        "allow to get data stream stats when" - {
          "the data stream name does match the allowed data stream names" excludeES(allEs6x, allEs7xBelowEs79x) in {
            val dataStream = DataStreamNameGenerator.next("test")
            createDataStream(dataStream, IndexTemplateNameGenerator.next)

            val dsm = new DataStreamManager(user1Client)
            val response = dsm.getDataStreamStats(dataStream)
            response.responseCode should be(200)
            response.responseCode should be(200)
            response.responseJson("data_stream_count").num.toInt should be(1)
            response.responseJson("backing_indices").num.toInt should be(1)
          }
        }
        "forbid to get data stream stats when" - {
          "the data stream name does not match the allowed data stream names" excludeES(allEs6x, allEs7xBelowEs79x) in {
            val dataStream = DataStreamNameGenerator.next("admin")
            createDataStream(dataStream, IndexTemplateNameGenerator.next)

            val dsm = new DataStreamManager(user1Client)
            val response = dsm.getDataStreamStats(dataStream)
            response.responseCode should be(401)
          }
        }
      }
    }
    "delete data stream" - {
      "without data_streams rule should" - {
        "allow to delete all data streams" excludeES(allEs6x, allEs7xBelowEs79x) in {
          val dataStream1 = DataStreamNameGenerator.next("admin")
          createDataStream(dataStream1, IndexTemplateNameGenerator.next)
          val response = adminDataStreamManager.deleteDataStream(dataStream1)
          response.responseCode should be(200)
        }
      }
      "with data_streams rule should" - {
        "allow to delete only allowed data streams when" - {
          "exact data stream matching" excludeES(allEs6x, allEs7xBelowEs79x) in {
            val dataStream = "data-stream-prod"
            createDataStream(dataStream, IndexTemplateNameGenerator.next)
            createDataStream(DataStreamNameGenerator.next("dev"), IndexTemplateNameGenerator.next)

            val dsm = new DataStreamManager(user2Client)
            val response = dsm.deleteDataStream(dataStream)
            response.responseCode should be(200)
          }
          "wildcard data stream matching" excludeES(allEs6x, allEs7xBelowEs79x) in {
            val dataStream1 = DataStreamNameGenerator.next("test")
            val dataStream2 = DataStreamNameGenerator.next("test")
            createDataStream(dataStream1, IndexTemplateNameGenerator.next)
            createDataStream(dataStream2, IndexTemplateNameGenerator.next)
            createDataStream("data-stream-prod", IndexTemplateNameGenerator.next)
            createDataStream(DataStreamNameGenerator.next("dev"), IndexTemplateNameGenerator.next)

            val dsm = new DataStreamManager(user1Client)
            val response = dsm.deleteDataStream(dataStream1)
            response.responseCode should be(200)
          }
        }
        "forbid to delete data stream when" - {
          "the data stream name does not match the allowed data stream names" excludeES(allEs6x, allEs7xBelowEs79x) in {
            val dataStream = DataStreamNameGenerator.next("admin")
            adminTemplateManager.createTemplate(IndexTemplateNameGenerator.next, indexTemplate(dataStream)).force()

            val dsm = new DataStreamManager(user2Client)
            val response = dsm.deleteDataStream(dataStream)
            response.responseCode should be(401)
          }
        }
      }
    }
    "should allow to rollover data stream" excludeES(allEs6x, allEs7xBelowEs79x) in {
      val dataStream = DataStreamNameGenerator.next("admin")
      createDataStream(dataStream, IndexTemplateNameGenerator.next)

      List.range(0, 2).foreach { _ =>
        createDocsInDataStream(dataStream, 1)
        adminIndexManager.rollover(dataStream).force()
      }

      val statsResponse = adminDataStreamManager.getDataStreamStats(dataStream)
      statsResponse.responseCode should be(200)
      statsResponse.responseJson("data_stream_count").num.toInt should be(1)
      statsResponse.responseJson("backing_indices").num.toInt should be(3)
    }
    "migrate index alias to data stream" - {
      "without indices rule should" - {
        "allow to migrate index alias to data stream" excludeES(allEs6x, allEs7xBelowEs711x) in {
          val dataStream = DataStreamNameGenerator.next("admin")
          adminDocumentManager.createDoc("logs-0001", 1, ujson.read(s"""{ "message":"test1", "@timestamp": "${format(Instant.now())}"}""")).force()
          adminDocumentManager.createDoc("logs-0001", 2, ujson.read(s"""{ "message":"test2", "@timestamp": "${format(Instant.now())}"}""")).force()
          adminDocumentManager.createDoc("logs-0001", 1, ujson.read(s"""{ "message":"test3", "@timestamp": "${format(Instant.now())}"}""")).force()
          adminIndexManager.createAliasOf("logs-0001", dataStream).force()

          adminTemplateManager.createTemplate(IndexTemplateNameGenerator.next, indexTemplate(dataStream)).force()

          val migrateToDataStreamResponse = adminDataStreamManager.migrateToDataStream(dataStream)
          migrateToDataStreamResponse.responseCode should be(200)

          val statsResponse = adminDataStreamManager.getDataStreamStats(dataStream)
          statsResponse.responseCode should be(200)
          statsResponse.responseJson("data_stream_count").num.toInt should be(1)
          statsResponse.responseJson("backing_indices").num.toInt should be(1)
        }
      }
      "with indices rule should" - {
        "allow to migrate index alias to data stream when" - {
          "the alias does match the allowed indices" excludeES(allEs6x, allEs7xBelowEs711x) in {
            val dataStream = DataStreamNameGenerator.next("test")
            adminDocumentManager.createDoc("logs-0001", 1, ujson.read(s"""{ "message":"test1", "@timestamp": "${format(Instant.now())}"}""")).force()
            adminDocumentManager.createDoc("logs-0001", 2, ujson.read(s"""{ "message":"test2", "@timestamp": "${format(Instant.now())}"}""")).force()
            adminDocumentManager.createDoc("logs-0001", 1, ujson.read(s"""{ "message":"test3", "@timestamp": "${format(Instant.now())}"}""")).force()
            adminIndexManager.createAliasOf("logs-0001", dataStream).force()

            adminTemplateManager.createTemplate(IndexTemplateNameGenerator.next, indexTemplate(dataStream)).force()

            val dsm = new DataStreamManager(user5Client)
            val migrateToDataStreamResponse = dsm.migrateToDataStream(dataStream)
            migrateToDataStreamResponse.responseCode should be(200)

            val statsResponse = adminDataStreamManager.getDataStreamStats(dataStream)
            statsResponse.responseCode should be(200)
            statsResponse.responseJson("data_stream_count").num.toInt should be(1)
            statsResponse.responseJson("backing_indices").num.toInt should be(1)
          }
        }
        "forbid to migrate index alias to data stream when" - {
          "the alias does not match the allowed indices" excludeES(allEs6x, allEs7xBelowEs711x) in {
            val dataStream = DataStreamNameGenerator.next("admin")
            adminDocumentManager.createDoc("logs-0001", 1, ujson.read(s"""{ "message":"test1", "@timestamp": "${format(Instant.now())}"}""")).force()
            adminDocumentManager.createDoc("logs-0001", 2, ujson.read(s"""{ "message":"test2", "@timestamp": "${format(Instant.now())}"}""")).force()
            adminDocumentManager.createDoc("logs-0001", 1, ujson.read(s"""{ "message":"test3", "@timestamp": "${format(Instant.now())}"}""")).force()
            adminIndexManager.createAliasOf("logs-0001", dataStream).force()

            adminTemplateManager.createTemplate(IndexTemplateNameGenerator.next, indexTemplate(dataStream)).force()

            val dsm = new DataStreamManager(user5Client)
            val migrateToDataStreamResponse = dsm.migrateToDataStream(dataStream)
            migrateToDataStreamResponse.responseCode should be(401)
          }
        }
      }
    }
    "modify data stream" - {
      "without data_streams rule should" - {
        "allow to modify data streams" excludeES(allEs6x, allEs7xBelowEs716x) in {
          val dataStream = DataStreamNameGenerator.next("admin")
          createDataStream(dataStream, IndexTemplateNameGenerator.next)

          List.range(0, 2).foreach { _ =>
            createDocsInDataStream(dataStream, 1)
            adminIndexManager.rollover(dataStream).force()
          }

          val dsIndices = dataStreamBackingIndices(dataStream)
          dsIndices.length should be(3)

          val modifyResponse = adminDataStreamManager.modifyDataStreams(ujson.read(
            s"""
               |{
               |  "actions": [
               |    {
               |      "remove_backing_index": {
               |        "data_stream": "$dataStream",
               |        "index": "${dsIndices.head}"
               |      }
               |    }
               |  ]
               |}
               |""".stripMargin))
          modifyResponse.responseCode should be(200)
        }
      }
      "with data_streams rule should" - {
        "allow to modify only allowed data streams when" - {
          "exact data stream matching" excludeES(allEs6x, allEs7xBelowEs716x) in {
            val dataStream = "data-stream-prod"
            createDataStream(dataStream, IndexTemplateNameGenerator.next)

            List.range(0, 2).foreach { _ =>
              createDocsInDataStream(dataStream, 1)
              adminIndexManager.rollover(dataStream).force()
            }
            val dsIndices = dataStreamBackingIndices(dataStream)
            dsIndices.length should be(3)

            val dsm = new DataStreamManager(user2Client)
            val modifyResponse = dsm.modifyDataStreams(ujson.read(
              s"""
                 |{
                 |  "actions": [
                 |    {
                 |      "remove_backing_index": {
                 |        "data_stream": "$dataStream",
                 |        "index": "${dsIndices.head}"
                 |      }
                 |    }
                 |  ]
                 |}
                 |""".stripMargin))
            modifyResponse.responseCode should be(200)
          }
          "wildcard data stream matching" excludeES(allEs6x, allEs7xBelowEs716x) in {
            val dataStream = DataStreamNameGenerator.next("test")
            createDataStream(dataStream, IndexTemplateNameGenerator.next)
            List.range(0, 2).foreach { _ =>
              createDocsInDataStream(dataStream, 1)
              adminIndexManager.rollover(dataStream).force()
            }

            val dsIndices = dataStreamBackingIndices(dataStream)
            dsIndices.length should be(3)

            val dsm = new DataStreamManager(user1Client)
            val modifyResponse = dsm.modifyDataStreams(ujson.read(
              s"""
                 |{
                 |  "actions": [
                 |    {
                 |      "remove_backing_index": {
                 |        "data_stream": "$dataStream",
                 |        "index": "${dsIndices.head}"
                 |      }
                 |    }
                 |  ]
                 |}
                 |""".stripMargin))
            modifyResponse.responseCode should be(200)
          }
        }
        "forbid to modify data streams when" - {
          "the data stream name does not match the allowed data stream names" excludeES(allEs6x, allEs7xBelowEs716x) in {
            val dataStream = DataStreamNameGenerator.next("admin")
            createDataStream(dataStream, IndexTemplateNameGenerator.next)
            List.range(0, 2).foreach { _ =>
              createDocsInDataStream(dataStream, 1)
              adminIndexManager.rollover(dataStream).force()
            }
            val dsIndices = dataStreamBackingIndices(dataStream)
            dsIndices.length should be(3)


            val dsm = new DataStreamManager(user1Client)
            val modifyResponse = dsm.modifyDataStreams(ujson.read(
              s"""
                 |{
                 |  "actions": [
                 |    {
                 |      "remove_backing_index": {
                 |        "data_stream": "$dataStream",
                 |        "index": "${dsIndices.head}"
                 |      }
                 |    }
                 |  ]
                 |}
                 |""".stripMargin))
            modifyResponse.responseCode should be(401)
          }
          "one of the data streams does not match the allowed data stream names" excludeES(allEs6x, allEs7xBelowEs716x) in {
            val dataStream = DataStreamNameGenerator.next("test")
            createDataStream(dataStream, IndexTemplateNameGenerator.next)
            val dsIndices = dataStreamBackingIndices(dataStream)
            dsIndices.length should be(1)
            val forbiddenDataStream = DataStreamNameGenerator.next("admin")
            createDataStream(forbiddenDataStream, IndexTemplateNameGenerator.next)
            val forbiddenDsIndices = dataStreamBackingIndices(forbiddenDataStream)
            forbiddenDsIndices.length should be(1)

            val dsm = new DataStreamManager(user1Client)
            val modifyResponse = dsm.modifyDataStreams(ujson.read(
              s"""
                 |{
                 |  "actions": [
                 |    {
                 |      "remove_backing_index": {
                 |        "data_stream": "$dataStream",
                 |        "index": "${dsIndices.head}"
                 |      }
                 |    },
                 |    {
                 |      "remove_backing_index": {
                 |        "data_stream": "$forbiddenDataStream",
                 |        "index": "${forbiddenDsIndices.head}"
                 |      }
                 |    }
                 |  ]
                 |}
                 |""".stripMargin))
            modifyResponse.responseCode should be(401)
          }
        }
      }
      "with indices rule should" - {
        "allow to modify only data streams when" - {
          "backing index name matches allowed indices" excludeES(allEs6x, allEs7xBelowEs716x) in {
            val dataStream = DataStreamNameGenerator.next("test")
            createDataStream(dataStream, IndexTemplateNameGenerator.next)

            List.range(0, 2).foreach { _ =>
              createDocsInDataStream(dataStream, 1)
              adminIndexManager.rollover(dataStream).force()
            }
            val dsIndices = dataStreamBackingIndices(dataStream)
            dsIndices.length should be(3)

            val dsm = new DataStreamManager(user3Client)
            val modifyResponse = dsm.modifyDataStreams(ujson.read(
              s"""
                 |{
                 |  "actions": [
                 |    {
                 |      "remove_backing_index": {
                 |        "data_stream": "$dataStream",
                 |        "index": "${dsIndices.head}"
                 |      }
                 |    }
                 |  ]
                 |}
                 |""".stripMargin))
            modifyResponse.responseCode should be(200)
          }
        }
        "forbid to modify data stream when" - {
          "backing index name does not match allowed indices" excludeES(allEs6x, allEs7xBelowEs716x) in {
            val dataStream = DataStreamNameGenerator.next("admin")
            createDataStream(dataStream, IndexTemplateNameGenerator.next)

            List.range(0, 2).foreach { _ =>
              createDocsInDataStream(dataStream, 1)
              adminIndexManager.rollover(dataStream).force()
            }
            val dsIndices = dataStreamBackingIndices(dataStream)
            dsIndices.length should be(3)

            val dsm = new DataStreamManager(user3Client)
            val modifyResponse = dsm.modifyDataStreams(ujson.read(
              s"""
                 |{
                 |  "actions": [
                 |    {
                 |      "remove_backing_index": {
                 |        "data_stream": "$dataStream",
                 |        "index": "${dsIndices.head}"
                 |      }
                 |    }
                 |  ]
                 |}
                 |""".stripMargin))
            modifyResponse.responseCode should be(401)
          }
        }
      }
    }
  }

  private def createDataStream(dataStreamName: String, indexTemplateName: String): Unit = {
    adminTemplateManager.createTemplate(indexTemplateName, indexTemplate(dataStreamName)).force()
    val createDataStreamResponse = adminDataStreamManager.createDataStream(dataStreamName)
    createDataStreamResponse.responseCode should be(200)
  }

  private def createDocsInDataStream(streamName: String, count: Int): Unit = {
    List.range(0, count).foreach { c =>
      val doc = ujson.read(s"""{ "message":"test$c", "@timestamp": "${format(Instant.now())}"}""")
      adminDocumentManager.createDocWithGeneratedId(streamName, doc).force()
    }
  }

  private def dataStreamBackingIndices(streamName: String): List[String] = {
    adminDataStreamManager
      .getDataStream(streamName)
      .force()
      .backingIndices
  }

  private def indexTemplate(dataStreamName: String) = ujson.read(
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

  override def beforeEach(): Unit = {
    if (Version.greaterOrEqualThan(esVersionUsed, 7, 9, 0)) {
      val dataStreamsResponse = adminDataStreamManager.getAllDataStreams().force()
      dataStreamsResponse.responseJson("data_streams").arr.foreach { entry =>
        adminDataStreamManager.deleteDataStream(entry("name").str).force()
      }

      adminTemplateManager
        .getTemplates
        .templates
        .filter(_.name.startsWith("index-template"))
        .foreach { template =>
          adminTemplateManager.deleteTemplate(template.name).force()
        }
    }
    super.beforeEach()
  }
}

private object DataStreamApiSuite {
  object IndexTemplateNameGenerator {
    private val uniquePart = Atomic(0)

    def next: String = s"index-template-${uniquePart.incrementAndGet()}"
  }

  object DataStreamNameGenerator {
    def next(infix: String): String = s"data-stream-$infix-$randomSuffix"

    private def randomSuffix: String = Random.alphanumeric.take(12).mkString.toLowerCase
  }
}
