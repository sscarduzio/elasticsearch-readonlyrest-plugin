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

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait BaseIndexApiSuite
  extends WordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupport {
  this: EsContainerCreator =>

  protected def notFoundIndexStatusReturned: Int
  protected def forbiddenStatusReturned: Int

  override def nodeDataInitializer = Some(BaseIndexApiSuite.nodeDataInitializer())

  private lazy val dev1IndexManager = new IndexManager(basicAuthClient("dev1", "test"))
  private lazy val dev2IndexManager = new IndexManager(basicAuthClient("dev2", "test"))
  private lazy val dev3IndexManager = new IndexManager(basicAuthClient("dev3", "test"))
  private lazy val dev5IndexManager = new IndexManager(basicAuthClient("dev5", "test"))
  private lazy val dev6IndexManager = new IndexManager(basicAuthClient("dev6", "test"))

  "ROR" when {
    "Get index API is used" should {
      "allow user to get index data" when {
        "he has access to it" when {
          "the index is called explicitly" in {
            val indexResponse = dev1IndexManager.getIndex("index1")

            indexResponse.responseCode should be(200)
            indexResponse.responseJson.obj.size should be(1)
            indexResponse.responseJson("index1")
          }
          "its alias is called" in {
            val indexResponse = dev1IndexManager.getIndex("index1_alias")

            indexResponse.responseCode should be(200)
            indexResponse.responseJson.obj.size should be(1)
            indexResponse.responseJson("index1")
          }
          "the index name with wildcard is used" when {
            "there is a matching index" in {
              val indexResponse = dev1IndexManager.getIndex("index*")

              indexResponse.responseCode should be(200)
              indexResponse.responseJson.obj.size should be(1)
              indexResponse.responseJson("index1")
            }
          }
          "the alias name with wildcard is used" when {
            "there is a matching alias" in {
              val indexResponse = dev1IndexManager.getIndex("index1_a*")

              indexResponse.responseCode should be(200)
              indexResponse.responseJson.obj.size should be(1)
              indexResponse.responseJson("index1")
            }
          }
          "one of called indices doesn't exist" in {
            val indexResponse = dev1IndexManager.getIndex("index1", "index3")

            indexResponse.responseCode should be(200)
            indexResponse.responseJson.obj.size should be(1)
            indexResponse.responseJson("index1")
          }
        }
        "he has access to its alias" when {
          "the alias is called" in {
            val indexResponse = dev2IndexManager.getIndex("index2_alias")

            indexResponse.responseCode should be(200)
            indexResponse.responseJson.obj.size should be(1)
            indexResponse.responseJson("index2")
          }
        }
      }
      "return empty response" when {
        "the index name with wildcard is used" when {
          "there is no matching index" in {
            val indexResponse = dev1IndexManager.getIndex("my_index*")

            indexResponse.responseCode should be(200)
            indexResponse.responseJson.obj.size should be(0)
          }
        }
        "the alias name with wildcard is used" when {
          "there is no matching alias" in {
            val indexResponse = dev1IndexManager.getIndex("my_index1_a*")

            indexResponse.responseCode should be(200)
            indexResponse.responseJson.obj.size should be(0)
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
    }
    "Get index alias API is used" should {
      "allow user to get aliases" when {
        "/_alias API is used" in {
          val aliasResponse = dev1IndexManager.getAliases

          aliasResponse.responseCode should be(200)
          aliasResponse.responseJson.obj.size should be(1)
          val aliasesJson = aliasResponse.responseJson("index1").obj("aliases").obj
          aliasesJson.size should be(1)
          aliasesJson.contains("index1_alias") should be(true)
        }
        "/[index]/_alias API is used" when {
          "index full name is passed" in {
            val aliasResponse = dev1IndexManager.getAlias("index1")

            aliasResponse.responseCode should be(200)
            aliasResponse.responseJson.obj.size should be(1)
            val aliasesJson = aliasResponse.responseJson("index1").obj("aliases").obj
            aliasesJson.size should be(1)
            aliasesJson.contains("index1_alias") should be(true)
          }
          "index name has wildcard" in {
            val aliasResponse = dev1IndexManager.getAlias("index*")

            aliasResponse.responseCode should be(200)
            aliasResponse.responseJson.obj.size should be(1)

            val aliasesJson = aliasResponse.responseJson("index1").obj("aliases").obj
            aliasesJson.size should be(1)
            aliasesJson.contains("index1_alias") should be(true)
          }
          "one of passed indices doesn't exist" in {
            val aliasResponse = dev1IndexManager.getAlias(indices = "index1", "nonexistent")

            aliasResponse.responseCode should be(200)
            aliasResponse.responseJson.obj.size should be(1)

            val aliasesJson = aliasResponse.responseJson("index1").obj("aliases").obj
            aliasesJson.size should be(1)
            aliasesJson.contains("index1_alias") should be(true)
          }
        }
        "/[index]/_alias/[alias] API is used" when {
          "alias full name is passed" in {
            val aliasResponse = dev1IndexManager.getAliasByName("index1", "index1_alias")

            aliasResponse.responseCode should be(200)
            aliasResponse.responseJson.obj.size should be(1)
            val aliasesJson = aliasResponse.responseJson("index1").obj("aliases").obj
            aliasesJson.size should be(1)
            aliasesJson.contains("index1_alias") should be(true)
          }
          "alias name has wildcard" in {
            val aliasResponse = dev1IndexManager.getAliasByName("index1", "index1*")

            aliasResponse.responseCode should be(200)
            aliasResponse.responseJson.obj.size should be(1)
            val aliasesJson = aliasResponse.responseJson("index1").obj("aliases").obj
            aliasesJson.size should be(1)
            aliasesJson.contains("index1_alias") should be(true)
          }
        }
      }
      "return empty response" when {
        "the index name with wildcard is used" when {
          "there is no matching index" in {
            val aliasResponse = dev1IndexManager.getAlias("nonexistent*")

            aliasResponse.responseCode should be(200)
            aliasResponse.responseJson.obj.size should be(0)
          }
        }
        "the alias name with wildcard is used" when {
          "there is no matching alias" excludeES("^es55x$".r, allEs6xExceptEs66x) in {
            val aliasResponse = dev1IndexManager.getAliasByName("index1", "nonexistent*")

            aliasResponse.responseCode should be(200)
            aliasResponse.responseJson.obj.size should be(0)
          }
        }
        "the full name alias is used" when {
          "there is no matching alias" excludeES("^es55x$".r, allEs6x, allEs7x) in {
            val aliasResponse = dev1IndexManager.getAliasByName("index1", "nonexistent")

            aliasResponse.responseCode should be(200)
            aliasResponse.responseJson.obj.size should be(0)
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
          "there is no matching alias" excludeES(allEs7x, "^es66x$".r) in {
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
  }
}

object BaseIndexApiSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val indexManager = new IndexManager(adminRestClient)

    documentManager.createDoc("index1", 1, ujson.read("""{"hello":"world"}""")).force()
    indexManager.createAliasOf("index1", "index1_alias").force()

    documentManager.createDoc("index2", 1, ujson.read("""{"hello":"world"}""")).force()
    indexManager.createAliasOf("index2", "index2_alias").force()

    documentManager.createDoc("index5-000001", 1, ujson.read("""{"hello":"world"}""")).force()
    indexManager.createAliasOf("index5-000001", "index5").force()
  }
}
