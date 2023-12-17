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
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyFreeSpecLike, SingletonPluginTestSupport}
import tech.beshu.ror.utils.elasticsearch.IndexManager.AliasAction
import tech.beshu.ror.utils.elasticsearch._
import tech.beshu.ror.utils.misc.{CustomScalaTestMatchers, Version}

import java.time.Instant
import scala.util.Random

class DataStreamApiSuite
  extends AnyFreeSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyFreeSpecLike
    with BeforeAndAfterEach
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/data_stream_api/readonlyrest.yml"

  private lazy val client = clients.head.adminClient
  private lazy val user1Client = clients.head.basicAuthClient("user1", "pass")
  private lazy val user2Client = clients.head.basicAuthClient("user2", "pass")
  private lazy val user3Client = clients.head.basicAuthClient("user3", "pass")
  private lazy val user4Client = clients.head.basicAuthClient("user4", "pass")
  private lazy val user5Client = clients.head.basicAuthClient("user5", "pass")
  private lazy val user11Client = clients.head.basicAuthClient("user11", "pass")
  private lazy val adminDocumentManager = new DocumentManager(client, esVersionUsed)
  private lazy val adminDataStreamManager = new DataStreamManager(client)
  private lazy val adminIndexManager = new IndexManager(client, esVersionUsed)
  private lazy val adminSearchManager = new SearchManager(client, esVersionUsed)
  private lazy val adminTemplateManager = new IndexTemplateManager(client, esVersionUsed)

  private val adminDataStream = DataStreamNameGenerator.next("admin")
  private val devDataStream = DataStreamNameGenerator.next("dev")
  private val testDataStream = DataStreamNameGenerator.next("test")

  "Data streams related" - {
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
        "allow to search by data stream alias" excludeES(allEs6x, allEs7xBelowEs714x) in {
          createDataStream(adminDataStream, IndexTemplateNameGenerator.next)
          createDocsInDataStream(adminDataStream, 2)

          adminIndexManager
            .updateAliases(AliasAction.Add(index = adminDataStream, alias = "ds-alias"))
            .force()

          val searchResponse = adminSearchManager.search("ds-alias")
          searchResponse.totalHits should be(2)
        }
        "allow to search by data stream alias with wildcard" excludeES(allEs6x, allEs7xBelowEs714x) in {
          createDataStream(adminDataStream, IndexTemplateNameGenerator.next)
          createDocsInDataStream(adminDataStream, 2)
          createDataStream(devDataStream, IndexTemplateNameGenerator.next)
          createDocsInDataStream(devDataStream, 1)

          adminIndexManager
            .updateAliases(
              AliasAction.Add(index = adminDataStream, alias = "ds-alias-1"),
              AliasAction.Add(index = devDataStream, alias = "ds-alias-2")
            )
            .force()

          val response = adminSearchManager.searchAll("ds-alias*")
          response.totalHits should be(3)
        }
        "allow to search by data stream backing index name" excludeES(allEs6x, allEs7xBelowEs79x) in {
          createDataStream(adminDataStream, IndexTemplateNameGenerator.next)
          createDocsInDataStream(adminDataStream, 1)
          adminIndexManager.rollover(adminDataStream).force()
          createDocsInDataStream(adminDataStream, 1)
          adminIndexManager.rollover(adminDataStream).force()
          createDocsInDataStream(adminDataStream, 1)

          val allDataStreamsResponse = adminDataStreamManager.getAllDataStreams().force()
          val indicesNames = allDataStreamsResponse.allBackingIndices
          indicesNames.foreach { indexName =>
            val response = adminSearchManager.searchAll(indexName)
            response.totalHits should be(1)
          }
        }
        "allow to search by data stream backing index with wildcard" excludeES(allEs6x, allEs7xBelowEs79x) in {
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
        "allow to search by data stream name" excludeES(allEs6x, allEs7xBelowEs79x) in {
          List(
            ("data-stream-admin", 4),
            ("data-stream-dev", 2),
            ("data-stream-test", 1)
          )
            .foreach { case (dataStream, docsCount) =>
              createDataStream(dataStream, IndexTemplateNameGenerator.next)
              createDocsInDataStream(dataStream, docsCount)
            }

          val searchManager = new SearchManager(clients.head.basicAuthClient("user7", "pass"), esVersionUsed)

          val dsAdminSearch = searchManager.searchAll("data-stream-admin")
          dsAdminSearch should have statusCode 401

          List(
            ("data-stream-dev", 2),
            ("data-stream-test", 1),
          )
            .foreach { case (dataStream, expectedHits) =>
              val response = searchManager.searchAll(dataStream)
              response.totalHits should be(expectedHits)
            }
        }
        "allow to search by data stream name with wildcard" excludeES(allEs6x, allEs7xBelowEs79x) in {
          List(
            ("data-stream-admin", 4),
            ("data-stream-dev", 2),
            ("data-stream-test", 1)
          )
            .foreach { case (dataStream, docsCount) =>
              createDataStream(dataStream, IndexTemplateNameGenerator.next)
              createDocsInDataStream(dataStream, docsCount)
            }

          val searchManager = new SearchManager(clients.head.basicAuthClient("user7", "pass"), esVersionUsed)

          List(
            ("data-stream-dev*", 2),
            ("data-stream-test*", 1),
            ("data-stream-admin*", 0),
            ("data-stream-*", 3),
          )
            .foreach { case (dataStream, expectedHits) =>
              val response = searchManager.searchAll(dataStream)
              response.totalHits should be(expectedHits)
            }
        }
        "allow to search by data stream alias" excludeES(allEs6x, allEs7xBelowEs714x) in {
          List(
            ("data-stream-admin", 4),
            ("data-stream-dev", 2),
            ("data-stream-test", 1)
          )
            .foreach { case (dataStream, docsCount) =>
              createDataStream(dataStream, IndexTemplateNameGenerator.next)
              createDocsInDataStream(dataStream, docsCount)
            }

          val searchManager = new SearchManager(clients.head.basicAuthClient("user8", "pass"), esVersionUsed)

          adminIndexManager
            .updateAliases(
              AliasAction.Add(index = "data-stream-test", alias = "data-stream-alias"),
              AliasAction.Add(index = "data-stream-dev", alias = "data-stream-alias"),
            )
            .force()

          val response = searchManager.searchAll("data-stream-alias")
          response.totalHits should be(3)
        }
        "allow to search by data stream alias with wildcard" excludeES(allEs6x, allEs7xBelowEs714x) in {
          List(
            ("data-stream-admin", 4),
            ("data-stream-dev", 2),
            ("data-stream-test", 1)
          )
            .foreach { case (dataStream, docsCount) =>
              createDataStream(dataStream, IndexTemplateNameGenerator.next)
              createDocsInDataStream(dataStream, docsCount)
            }

          val searchManager = new SearchManager(clients.head.basicAuthClient("user8", "pass"), esVersionUsed)

          adminIndexManager
            .updateAliases(
              AliasAction.Add(index = "data-stream-test", alias = "data-stream-alias"),
              AliasAction.Add(index = "data-stream-dev", alias = "data-stream-alias"),
            )
            .force()

          val response = searchManager.searchAll("data-stream-alias")
          response.totalHits should be(3)
        }
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

          val searchManager = new SearchManager(user3Client, esVersionUsed)

          List(
            (s".ds-$devDataStream*", 2),
            (s".ds-$testDataStream*", 1),
            (s".ds-$adminDataStream*", 0),
          )
            .foreach { case (dataStreamIndexPattern, expectedHits) =>
              val response = searchManager.searchAll(dataStreamIndexPattern)
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

          val searchManager = new SearchManager(user3Client, esVersionUsed)
          val getAllResponse = adminDataStreamManager.getAllDataStreams().force()

          def findIndicesForDataStream(name: String) =
            getAllResponse.backingIndicesByDataStream(name)

          findIndicesForDataStream(adminDataStream).foreach { indexName =>
            val response = searchManager.searchAll(indexName)
            response should have statusCode 401
          }
          findIndicesForDataStream(devDataStream).foreach { indexName =>
            val response = searchManager.searchAll(indexName)
            response.totalHits should be(2)
          }
          findIndicesForDataStream(testDataStream).foreach { indexName =>
            val response = searchManager.searchAll(indexName)
            response.totalHits should be(1)
          }
        }
      }
    }
    "Document API" - {
      "without indices rule should" - {
        "allow to add documents to data stream" excludeES(allEs6x, allEs7xBelowEs79x) in {
          val dataStream = DataStreamNameGenerator.next("admin")
          createDataStream(dataStream, IndexTemplateNameGenerator.next)
          createDocsInDataStream(dataStream, 1)
        }
      }
      "with indices rule should" - {
        "allow to add documents to data stream when" - {
          "data stream name configured" excludeES(allEs6x, allEs7xBelowEs79x) in {
            List(
              "data-stream-admin",
              "data-stream-dev",
              "data-stream-test"
            )
              .foreach { dataStream =>
                createDataStream(dataStream, IndexTemplateNameGenerator.next)
              }

            val documentManager = new DocumentManager(clients.head.basicAuthClient("user7", "pass"), esVersionUsed)

            documentManager
              .createDocWithGeneratedId("data-stream-admin", documentJson) should have statusCode 401

            documentManager
              .createDocWithGeneratedId("data-stream-dev", documentJson)
              .force()

            documentManager
              .createDocWithGeneratedId("data-stream-test", documentJson)
              .force()
          }
          "data stream name wildcard configured" excludeES(allEs6x, allEs7xBelowEs79x) in {
            List(
              "data-stream-admin",
              "data-stream-dev",
              "data-stream-test"
            )
              .foreach { dataStream =>
                createDataStream(dataStream, IndexTemplateNameGenerator.next)
              }

            val documentManager = new DocumentManager(clients.head.basicAuthClient("user10", "pass"), esVersionUsed)

            documentManager
              .createDocWithGeneratedId("data-stream-admin", documentJson) should have statusCode 401

            documentManager
              .createDocWithGeneratedId("data-stream-dev", documentJson)
              .force()

            documentManager
              .createDocWithGeneratedId("data-stream-test", documentJson)
              .force()
          }
        }
      }

    }
    "allow to add documents to data stream" excludeES(allEs6x, allEs7xBelowEs79x) in {
      val dataStream = DataStreamNameGenerator.next("admin")
      createDataStream(dataStream, IndexTemplateNameGenerator.next)
      createDocsInDataStream(dataStream, 1)
    }
    "should allow to rollover data stream" excludeES(allEs6x, allEs7xBelowEs79x) in {
      val dataStream = DataStreamNameGenerator.next("admin")
      createDataStream(dataStream, IndexTemplateNameGenerator.next)

      List.range(0, 2).foreach { _ =>
        createDocsInDataStream(dataStream, 1)
        adminIndexManager.rollover(dataStream).force()
      }

      val statsResponse = adminDataStreamManager.getDataStreamStats(dataStream)
      statsResponse should have statusCode 200
      statsResponse.dataStreamsCount should be(1)
      statsResponse.backingIndicesCount should be(3)
    }
  }

  "Data stream API" - {
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
              response should have statusCode 200
            }
            "wildcard data stream matching" excludeES(allEs6x, allEs7xBelowEs79x) in {
              val dataStream = DataStreamNameGenerator.next("test")
              adminTemplateManager.createTemplate(IndexTemplateNameGenerator.next, indexTemplate(dataStream)).force()

              val dsm = new DataStreamManager(user1Client)
              val response = dsm.createDataStream(dataStream)
              response should have statusCode 200
            }
          }
        }
        "forbid to create data stream when" - {
          "the data stream name does not match the allowed data stream names" excludeES(allEs6x, allEs7xBelowEs79x) in {
            val dataStream = DataStreamNameGenerator.next("admin")
            adminTemplateManager.createTemplate(IndexTemplateNameGenerator.next, indexTemplate(dataStream)).force()

            val dsm = new DataStreamManager(user2Client)
            val response = dsm.createDataStream(dataStream)
            response should have statusCode 401
          }
        }
      }
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
            response should have statusCode 200
            response.allDataStreams.toSet should be(Set(dataStream1, dataStream2))
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
              response should have statusCode 200
              response.allDataStreams.toSet should be(Set(dataStream))
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
              response should have statusCode 200
              response.allDataStreams.toSet should be(Set(dataStream1, dataStream2))
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
              response should have statusCode 200
              response.allDataStreams.toSet should be(Set(dataStream1, dataStream2))
            }
            "user has access to all backing indices" excludeES(allEs6x, allEs7xBelowEs79x) in {
              val dataStream1 = DataStreamNameGenerator.next("admin")
              val dataStream2 = DataStreamNameGenerator.next("dev")

              createDataStream(dataStream1, IndexTemplateNameGenerator.next)
              createDataStream(dataStream2, IndexTemplateNameGenerator.next)

              val dsm = new DataStreamManager(user4Client)
              val response = dsm.getAllDataStreams()
              response should have statusCode 200
              response.allDataStreams.toSet should be(Set(dataStream1, dataStream2))
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
              response should have statusCode 200
              response.allDataStreams.toSet should be(Set(dataStream1, dataStream2))
            }
            "in the allowed indices list there is only the name of the stream" excludeES(allEs6x, allEs7xBelowEs79x) in {
              createDataStream("user11_index1", IndexTemplateNameGenerator.next)
              createDataStream("user11_index200", IndexTemplateNameGenerator.next)
              createDataStream("user11_index300", IndexTemplateNameGenerator.next)

              val dsm = new DataStreamManager(user11Client)
              val response = dsm.getAllDataStreams()

              response should have statusCode 200
              response.allDataStreams.toSet should be(Set("user11_index1", "user11_index200"))
            }
          }
          "return empty list of data streams when" - {
            "the user has no access to any backing indices" excludeES(allEs6x, allEs7xBelowEs79x) in {
              val dataStream = DataStreamNameGenerator.next("admin")
              createDataStream(dataStream, IndexTemplateNameGenerator.next)

              val dsm = new DataStreamManager(user1Client)
              val response = dsm.getAllDataStreams()
              response should have statusCode 200
              response.allDataStreams.toSet should be(Set.empty)
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

            val response = adminDataStreamManager.getDataStream(dataStream1)
            response should have statusCode 200
            response.dataStreamName should be(dataStream1)
          }
        }
        "with data_streams rule should" - {
          "exact data stream matching" excludeES(allEs6x, allEs7xBelowEs79x) in {
            val dataStream = "data-stream-prod"
            createDataStream(dataStream, IndexTemplateNameGenerator.next)
            createDataStream(DataStreamNameGenerator.next("dev"), IndexTemplateNameGenerator.next)

            val dsm = new DataStreamManager(user2Client)
            val response = dsm.getDataStream(dataStream)
            response should have statusCode 200
            response.dataStreamName should be(dataStream)
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
            response should have statusCode 200
            response.dataStreamName should be(dataStream1)
          }
        }
        "with indices rule should" - {
          "allow to get data stream when" - {
            "the index name does match the allowed indices" excludeES(allEs6x, allEs7xBelowEs79x) in {
              val dataStream = DataStreamNameGenerator.next("test")
              createDataStream(dataStream, IndexTemplateNameGenerator.next)

              val dsm = new DataStreamManager(user3Client)
              val response = dsm.getDataStream(dataStream)
              response should have statusCode 200
              response.dataStreamName should be(dataStream)
            }
          }
          "return empty data streams when" - {
            "the index name does not match the allowed indices" excludeES(allEs6x, allEs7xBelowEs79x) in {
              val dataStream = DataStreamNameGenerator.next("admin")
              createDataStream(dataStream, IndexTemplateNameGenerator.next)

              val dsm = new DataStreamManager(user3Client)
              val response = dsm.getDataStream(dataStream)
              response should have statusCode 200
              response.allDataStreams.toSet should be(Set.empty)
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
          response should have statusCode 200
          response.dataStreamsCount should be(2)
          response.backingIndicesCount should be(2)
        }
      }
      "with data_streams rule should" - {
        "allow to get data stream stats when" - {
          "the data stream name does match the allowed data stream names" excludeES(allEs6x, allEs7xBelowEs79x) in {
            val dataStream = DataStreamNameGenerator.next("test")
            createDataStream(dataStream, IndexTemplateNameGenerator.next)

            val dsm = new DataStreamManager(user1Client)
            val response = dsm.getDataStreamStats(dataStream)
            response should have statusCode 200
            response.dataStreamsCount should be(1)
            response.backingIndicesCount should be(1)
          }
        }
        "forbid to get data stream stats when" - {
          "the data stream name does not match the allowed data stream names" excludeES(allEs6x, allEs7xBelowEs79x) in {
            val dataStream = DataStreamNameGenerator.next("admin")
            createDataStream(dataStream, IndexTemplateNameGenerator.next)

            val dsm = new DataStreamManager(user1Client)
            val response = dsm.getDataStreamStats(dataStream)
            response should have statusCode 401
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
          response should have statusCode 200
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
            response should have statusCode 200
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
            response should have statusCode 200
          }
        }
        "forbid to delete data stream when" - {
          "the data stream name does not match the allowed data stream names" excludeES(allEs6x, allEs7xBelowEs79x) in {
            val dataStream = DataStreamNameGenerator.next("admin")
            adminTemplateManager.createTemplate(IndexTemplateNameGenerator.next, indexTemplate(dataStream)).force()

            val dsm = new DataStreamManager(user2Client)
            val response = dsm.deleteDataStream(dataStream)
            response should have statusCode 401
          }
        }
      }
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
          migrateToDataStreamResponse should have statusCode 200

          val statsResponse = adminDataStreamManager.getDataStreamStats(dataStream)
          statsResponse should have statusCode 200
          statsResponse.dataStreamsCount should be(1)
          statsResponse.backingIndicesCount should be(1)
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
            migrateToDataStreamResponse should have statusCode 200

            val statsResponse = adminDataStreamManager.getDataStreamStats(dataStream)
            statsResponse should have statusCode 200
            statsResponse.dataStreamsCount should be(1)
            statsResponse.backingIndicesCount should be(1)
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
            migrateToDataStreamResponse should have statusCode 401
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
          modifyResponse should have statusCode 200
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
            modifyResponse should have statusCode 200
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
            modifyResponse should have statusCode 200
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
            modifyResponse should have statusCode 401
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
            modifyResponse should have statusCode 401
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
            modifyResponse should have statusCode 200
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
            modifyResponse should have statusCode 401
          }
        }
      }
    }
  }

  private def createDataStream(dataStreamName: String, indexTemplateName: String): Unit = {
    adminTemplateManager.createTemplate(indexTemplateName, indexTemplate(dataStreamName)).force()
    val createDataStreamResponse = adminDataStreamManager.createDataStream(dataStreamName)
    createDataStreamResponse should have statusCode 200
  }

  private def createDocsInDataStream(streamName: String, count: Int): Unit = {
    List.range(0, count).foreach { c =>
      val doc = ujson.read(s"""{ "message":"test$c", "@timestamp": "${format(Instant.now())}"}""")
      adminDocumentManager.createDocWithGeneratedId(streamName, doc).force()
    }
  }

  private def documentJson: ujson.Value = {
    ujson.read(s"""{ "message":"test", "@timestamp": "${format(Instant.now())}"}""")
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
      dataStreamsResponse.allDataStreams.foreach { dataStream =>
        adminDataStreamManager.deleteDataStream(dataStream).force()
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
