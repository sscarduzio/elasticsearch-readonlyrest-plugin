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
package tech.beshu.ror.integration.suites.base

import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterProvider}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait BaseIndexApiSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupportForAnyWordSpecLike {
  this: EsClusterProvider =>

  protected def notFoundIndexStatusReturned: Int
  protected def forbiddenStatusReturned: Int

  override def nodeDataInitializer = Some(BaseIndexApiSuite.nodeDataInitializer())

  private lazy val dev1IndexManager = new IndexManager(basicAuthClient("dev1", "test"), esVersionUsed)
  private lazy val dev2IndexManager = new IndexManager(basicAuthClient("dev2", "test"), esVersionUsed)
  private lazy val dev3IndexManager = new IndexManager(basicAuthClient("dev3", "test"), esVersionUsed)
  private lazy val dev5IndexManager = new IndexManager(basicAuthClient("dev5", "test"), esVersionUsed)
  private lazy val dev6IndexManager = new IndexManager(basicAuthClient("dev6", "test"), esVersionUsed)
  private lazy val dev7IndexManager = new IndexManager(basicAuthClient("dev7", "test"), esVersionUsed)

  "ROR" when {
    "Get index API is used" should {
      "allow user to get index data" when {
        "he has access to it" when {
          "the index is called explicitly" in {
            val indexResponse = dev1IndexManager.getIndex("index1")

            indexResponse.responseCode should be(200)
            indexResponse.indicesAndAliases should be (Map(
              "index1" -> Set("index1_alias")
            ))
          }
          "its alias is called" in {
            val indexResponse = dev1IndexManager.getIndex("index1_alias")

            indexResponse.responseCode should be(200)
            indexResponse.indicesAndAliases should be (Map(
              "index1" -> Set("index1_alias")
            ))
          }
          "the index name with wildcard is used" when {
            "there is a matching index" in {
              val indexResponse = dev1IndexManager.getIndex("index*")

              indexResponse.responseCode should be(200)
              indexResponse.indicesAndAliases should be (Map(
                "index1" -> Set("index1_alias")
              ))
            }
          }
          "the alias name with wildcard is used" when {
            "there is a matching alias" in {
              val indexResponse = dev1IndexManager.getIndex("index1_a*")

              indexResponse.responseCode should be(200)
              indexResponse.indicesAndAliases should be (Map(
                "index1" -> Set("index1_alias")
              ))
            }
          }
          "one of called indices doesn't exist" in {
            val indexResponse = dev1IndexManager.getIndex("index1", "index3")

            indexResponse.responseCode should be(200)
            indexResponse.indicesAndAliases should be (Map(
              "index1" -> Set("index1_alias")
            ))
          }
        }
        "he has access to its alias" when {
          "the alias is called" in {
            val indexResponse = dev2IndexManager.getIndex("index2_alias")

            indexResponse.responseCode should be(200)
            indexResponse.indicesAndAliases should be (Map(
              "index2" -> Set("index2_alias")
            ))
          }
        }
      }
      "return empty response" when {
        "the index name with wildcard is used" when {
          "there is no matching index" in {
            val indexResponse = dev1IndexManager.getIndex("my_index*")

            indexResponse.responseCode should be(200)
            indexResponse.indicesAndAliases should be (Map.empty)
          }
        }
        "the alias name with wildcard is used" when {
          "there is no matching alias" in {
            val indexResponse = dev1IndexManager.getIndex("my_index1_a*")

            indexResponse.responseCode should be(200)
            indexResponse.indicesAndAliases should be (Map.empty)
          }
        }
      }
      "pretend that index doesn't exist" when {
        "called index really doesn't exist" in {
          val indexResponse = dev1IndexManager.getIndex("index3")

          indexResponse.responseCode should be(notFoundIndexStatusReturned)
        }
        "called index exists, but the user has no access to it" in {
          val indexResponse = dev1IndexManager.getIndex("index2")

          indexResponse.responseCode should be(notFoundIndexStatusReturned)
        }
        "the index is called explicitly when user has configured alias in indices rule" in {
          val indexResponse = dev6IndexManager.getIndex("index2")

          indexResponse.responseCode should be(notFoundIndexStatusReturned)
        }
      }
      "filter out one not allowed alias" in {
        val indexResponse = dev7IndexManager.getIndex("index7*")

        indexResponse.responseCode should be(200)
        indexResponse.indicesAndAliases should be (Map(
          "index7-000001" -> Set("index7"),
          "index7-000002" -> Set.empty
        ))
      }
    }
    "Get index alias API is used" should {
      "allow user to get aliases" when {
        "/_alias API is used" in {
          val aliasResponse = dev1IndexManager.getAliases

          aliasResponse.responseCode should be(200)
          aliasResponse.aliasesOfIndices should be (Map(
            "index1" -> Set("index1_alias")
          ))
        }
        "/[index]/_alias API is used" when {
          "index full name is passed" in {
            val aliasResponse = dev1IndexManager.getAlias("index1")

            aliasResponse.responseCode should be(200)
            aliasResponse.aliasesOfIndices should be (Map(
              "index1" -> Set("index1_alias")
            ))
          }
          "index name has wildcard" in {
            val aliasResponse = dev1IndexManager.getAlias("index*")

            aliasResponse.responseCode should be(200)
            aliasResponse.aliasesOfIndices should be (Map(
              "index1" -> Set("index1_alias")
            ))
          }
          "one of passed indices doesn't exist" in {
            val aliasResponse = dev1IndexManager.getAlias(indices = "index1", "nonexistent")

            aliasResponse.responseCode should be(200)
            aliasResponse.aliasesOfIndices should be (Map(
              "index1" -> Set("index1_alias")
            ))
          }
          "one index has no aliases" in {
            val aliasResponse = dev7IndexManager.getAlias("index7*")

            aliasResponse.responseCode should be(200)
            aliasResponse.aliasesOfIndices should be (Map(
              "index7-000001" -> Set("index7"),
              "index7-000002" -> Set.empty
            ))
          }
        }
        "/[index]/_alias/[alias] API is used" when {
          "alias full name is passed" in {
            val aliasResponse = dev1IndexManager.getAliasByName("index1", "index1_alias")

            aliasResponse.responseCode should be(200)
            aliasResponse.aliasesOfIndices should be (Map(
              "index1" -> Set("index1_alias")
            ))
          }
          "alias name has wildcard" in {
            val aliasResponse = dev1IndexManager.getAliasByName("index1", "index1*")

            aliasResponse.responseCode should be(200)
            aliasResponse.aliasesOfIndices should be (Map(
              "index1" -> Set("index1_alias")
            ))
          }
        }
      }
      "return empty response" when {
        "the index name with wildcard is used" when {
          "there is no matching index" in {
            val aliasResponse = dev1IndexManager.getAlias("nonexistent*")

            aliasResponse.responseCode should be(200)
            aliasResponse.aliasesOfIndices should be (Map.empty)
          }
        }
        "the alias name with wildcard is used" when {
          "there is no matching alias" excludeES(allEs6xExceptEs67x) in {
            val aliasResponse = dev1IndexManager.getAliasByName("index1", "nonexistent*")

            aliasResponse.responseCode should be(200)
            aliasResponse.aliasesOfIndices should be (Map.empty)
          }
        }
      }
      "pretend that index doesn't exist" when {
        "called index really doesn't exist" in {
          val aliasResponse = dev1IndexManager.getAlias("nonexistent")

          aliasResponse.responseCode should be(notFoundIndexStatusReturned)
        }
        "called index exists, but the user has no access to it" in {
          val aliasResponse = dev1IndexManager.getAlias("index2")

          aliasResponse.responseCode should be(notFoundIndexStatusReturned)
        }
        "the alias name with wildcard is used" when {
          "there is no matching alias" excludeES("^es67x$".r, allEs7x, allEs8x) in {
            val aliasResponse = dev1IndexManager.getAliasByName("index1", "nonexistent*")

            aliasResponse.responseCode should be(404)
          }
        }
      }
      "return alias not found" when {
        "full alias name is used and the alias doesn't exist" in {
          val aliasResponse = dev1IndexManager.getAliasByName("index1", "nonexistent")

          aliasResponse.responseCode should be(notFoundIndexStatusReturned)
        }
      }
    }
    "Get settings API is used" should {
      "allow user to get index settings" when {
        "he has access to the index" when {
          "the index is called explicitly" in {
            val indexResponse = dev1IndexManager.getSettings("index1")

            indexResponse.responseCode should be(200)
            indexResponse.responseJson.obj.size should be(1)
            indexResponse.responseJson("index1")
          }
          "_all is used" in {
            val indexResponse = dev1IndexManager.getAllSettings

            indexResponse.responseCode should be(200)
            indexResponse.responseJson.obj.size should be(1)
            indexResponse.responseJson("index1")
          }
          "its alias is called" in {
            val indexResponse = dev1IndexManager.getSettings("index1_alias")

            indexResponse.responseCode should be(200)
            indexResponse.responseJson.obj.size should be(1)
            indexResponse.responseJson("index1")
          }
          "the index name with wildcard is used" when {
            "there is a matching index" in {
              val indexResponse = dev1IndexManager.getSettings("index*")

              indexResponse.responseCode should be(200)
              indexResponse.responseJson.obj.size should be(1)
              indexResponse.responseJson("index1")
            }
          }
          "the alias name with wildcard is used" when {
            "there is a matching alias" in {
              val indexResponse = dev1IndexManager.getSettings("index1_a*")

              indexResponse.responseCode should be(200)
              indexResponse.responseJson.obj.size should be(1)
              indexResponse.responseJson("index1")
            }
          }
          "one of called indices doesn't exist" in {
            val indexResponse = dev1IndexManager.getSettings("index1", "index3")

            indexResponse.responseCode should be(200)
            indexResponse.responseJson.obj.size should be(1)
            indexResponse.responseJson("index1")
          }
        }
        "he has access to the index's alias" when {
          "the alias is called" in {
            val indexResponse = dev2IndexManager.getSettings("index2_alias")

            indexResponse.responseCode should be(200)
            indexResponse.responseJson.obj.size should be(1)
            indexResponse.responseJson("index2")
          }
        }
      }
      "return empty response" when {
        "the index name with wildcard is used" when {
          "there is no matching index" in {
            val indexResponse = dev1IndexManager.getSettings("my_index*")

            indexResponse.responseCode should be(200)
            indexResponse.responseJson.obj.size should be(0)
          }
        }
        "the alias name with wildcard is used" when {
          "there is no matching alias" in {
            val indexResponse = dev1IndexManager.getSettings("my_index1_a*")

            indexResponse.responseCode should be(200)
            indexResponse.responseJson.obj.size should be(0)
          }
        }
        "_all settings is used and user doesn't have indices" in {
          val indexResponse = dev3IndexManager.getAllSettings

          indexResponse.responseCode should be(200)
          indexResponse.responseJson.obj.size should be(0)
        }
      }
      "pretend that index doesn't exist" when {
        "called index really doesn't exist" in {
          val indexResponse = dev1IndexManager.getSettings("index3")

          indexResponse.responseCode should be(notFoundIndexStatusReturned)
        }
        "called index exists, but the user has no access to it" in {
          val indexResponse = dev1IndexManager.getSettings("index2")

          indexResponse.responseCode should be(notFoundIndexStatusReturned)
        }
        "the index is called explicitly when user has configured alias in indices rule" in {
          val indexResponse = dev6IndexManager.getSettings("index2")

          indexResponse.responseCode should be(notFoundIndexStatusReturned)
        }
      }
    }
    "Rollover API is used" should {
      "be allowed" when {
        "user has access to rollover target and rollover index (defined)" in {
          val result = dev5IndexManager.rollover("index5", "index5-000010")

          result.responseCode should be (200)
        }
        "user gas access to rollover target (rollover index not defined)" in {
          val result = dev5IndexManager.rollover("index5")

          result.responseCode should be (200)
        }
      }
      "not be allowed" when {
        "user has no access to rollover target" in {
          val result = dev5IndexManager.rollover("index1")

          result.responseCode should be (forbiddenStatusReturned)
        }
        "user has no access to rollover index" in {
          val result = dev5IndexManager.rollover("index5", "index1")

          result.responseCode should be (forbiddenStatusReturned)
        }
      }
    }
    "Resolve index API is used" should {
      "be allowed" when {
        "user has access to the requested index" excludeES (allEs6x, allEs7xBelowEs79x) in {
          val result = dev7IndexManager.resolve("index7-000001")

          result.responseCode should be (200)

          result.indices.size should be (1)
          result.indices.head.name should be ("index7-000001")
          result.indices.head.aliases should be (List("index7"))

          result.aliases.size should be (0)
        }
        "user has access to the requested index pattern" excludeES (allEs6x, allEs7xBelowEs79x) in {
          val result = dev7IndexManager.resolve("index7*")

          result.responseCode should be (200)

          result.indices.size should be (2)
          result.indices.head.name should be ("index7-000001")
          result.indices.head.aliases should be (List("index7"))
          result.indices(1).name should be ("index7-000002")
          result.indices(1).aliases should be (List.empty)

          result.aliases.size should be (1)
          result.aliases.head.name should be ("index7")
          result.aliases.head.indices should be (List("index7-000001"))
        }
        "user has access to narrowed index pattern" excludeES (allEs6x, allEs7xBelowEs79x) in {
          val result = dev7IndexManager.resolve("*")

          result.responseCode should be (200)

          result.indices.size should be (2)
          result.indices.head.name should be ("index7-000001")
          result.indices.head.aliases should be (List("index7"))
          result.indices(1).name should be ("index7-000002")
          result.indices(1).aliases should be (List.empty)

          result.aliases.size should be (1)
          result.aliases.head.name should be ("index7")
          result.aliases.head.indices should be (List("index7-000001"))
        }
      }
      "return empty result" when {
        "user has no access to requested index pattern" excludeES (allEs6x, allEs7xBelowEs79x) in {
          val result = dev7IndexManager.resolve("index2*")

          result.responseCode should be (200)
          result.indices.size should be (0)
          result.aliases.size should be (0)
        }
        "user has no access to the requested index" excludeES (allEs6x, allEs7xBelowEs79x) in {
          val result = dev7IndexManager.resolve("index2")

          result.responseCode should be (200)
          result.indices.size should be (0)
          result.aliases.size should be (0)
        }
      }
    }
  }
}

object BaseIndexApiSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val indexManager = new IndexManager(adminRestClient, esVersion)

    documentManager.createDoc("index1", 1, ujson.read("""{"hello":"world"}""")).force()
    indexManager.createAliasOf("index1", "index1_alias").force()

    documentManager.createDoc("index2", 1, ujson.read("""{"hello":"world"}""")).force()
    indexManager.createAliasOf("index2", "index2_alias").force()

    documentManager.createDoc("index5-000001", 1, ujson.read("""{"hello":"world"}""")).force()
    indexManager.createAliasOf("index5-000001", "index5").force()

    documentManager.createDoc("index7-000001", 1, ujson.read("""{"hello":"world"}""")).force()
    indexManager.createAliasOf("index7-000001", "index7").force()
    indexManager.createAliasOf("index7-000001", "special_index7").force()
    documentManager.createDoc("index7-000002", 1, ujson.read("""{"hello":"world"}""")).force()
  }
}
