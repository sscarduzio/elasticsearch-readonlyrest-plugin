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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, SingletonLdapContainers}
import tech.beshu.ror.utils.containers.dependencies.ldap
import tech.beshu.ror.utils.containers.{DependencyDef, ElasticsearchNodeDataInitializer, EsClusterProvider}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait LdapIntegrationSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupportForAnyWordSpecLike
    with Matchers {
  this: EsClusterProvider =>

  override implicit val rorConfigFileName = "/ldap_integration/readonlyrest.yml"

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some(LdapIntegrationSuite.nodeDataInitializer())

  override def clusterDependencies: List[DependencyDef] = List(
    ldap(name = "LDAP1", SingletonLdapContainers.ldap1),
    ldap(name = "LDAP2", SingletonLdapContainers.ldap2)
  )

  private lazy val cartmanIndexManager = new IndexManager(basicAuthClient("cartman", "user2"), esVersionUsed)
  private lazy val chandlerIndexManager = new IndexManager(basicAuthClient("bong", "user1"), esVersionUsed)
  private lazy val morganIndexManager = new IndexManager(basicAuthClient("morgan", "user1"), esVersionUsed)
  private lazy val bilboIndexManager = new IndexManager(basicAuthClient("Bìlbö Bággįnš", "user2"), esVersionUsed)
  private lazy val jesusIndexManager = new IndexManager(basicAuthClient("jesus", "user1"), esVersionUsed)
  private lazy val allahIndexManager = new IndexManager(basicAuthClient("allah", "user2"), esVersionUsed)

  private def indexManagerWithHeader(client: RestClient, header: (String, String)) =
    new IndexManager(client, esVersionUsed, additionalHeaders = Map(header))

  "Test1 index" can {
    "be seen" when {
      "users, which belong to group1, request it" when {
        "no current group is sent" in {
          val cartmanResult = cartmanIndexManager.getIndex("test1")
          cartmanResult.responseCode should be(200)

          val chandlerResult = chandlerIndexManager.getIndex("test1")
          chandlerResult.responseCode should be(200)
        }
        "current group is sent in ROR header" when {
          "the group is group1" in {
            val indexManager = indexManagerWithHeader(
              basicAuthClient("cartman", "user2"),
              "x-ror-current-group" -> "group1"
            )

            val result = indexManager.getIndex("test1")
            result.responseCode should be(200)
          }
          "the group is group3" in {
            val indexManager = indexManagerWithHeader(
              basicAuthClient("cartman", "user2"),
              "x-ror-current-group" -> "group3"
            )

            val result = indexManager.getIndex("test1")
            result.responseCode should be(200)
          }
        }
        "current group is sent in authorization header metadata" in {
          val indexManager = indexManagerWithHeader(
            noBasicAuthClient,
            "Authorization" -> "Basic Y2FydG1hbjp1c2VyMg==, ror_metadata=eyJoZWFkZXJzIjpbIngtcm9yLWN1cnJlbnQtZ3JvdXA6Z3JvdXAxIiwgImhlYWRlcjE6eHl6Il19"
          )

          val result = indexManager.getIndex("test1")
          result.responseCode should be(200)
        }
      }
    }
    "not be seen" when {
      "users, which don't belong to group1, request it" in {
        val response = bilboIndexManager.getIndex("test1")
        response.responseCode should be(404)
      }
      "user cannot be authenticated" in {
        val indexManager = new IndexManager(basicAuthClient("cartman", "wrong_password"), esVersionUsed)
        val response = indexManager.getIndex("test1")

        response.responseCode should be(403)
      }
    }
  }

  "Test2 index" can {
    "be seen" when {
      "users, which belong to group4, request it" in {
        val cartmanResult = cartmanIndexManager.getIndex("test2")
        cartmanResult.responseCode should be(200)

        val chandlerResult = chandlerIndexManager.getIndex("test2")
        chandlerResult.responseCode should be(200)

        val morganResult = morganIndexManager.getIndex("test2")
        morganResult.responseCode should be(200)
      }
    }
  }

  "Test3 index" can {
    "be seen" when {
      "users, which belong to local_group1, request it" when {
        "no current group is sent" in {
          val cartmanResult = cartmanIndexManager.getIndex("test3")
          cartmanResult.responseCode should be(200)

          val bilboResult = bilboIndexManager.getIndex("test3")
          bilboResult.responseCode should be(200)
        }
        "current group is sent in ROR header" when {
          "the group is local_group1" in {
            val indexManager = indexManagerWithHeader(
              basicAuthClient("cartman", "user2"),
              "x-ror-current-group" -> "local_group1"
            )

            val result = indexManager.getIndex("test3")
            result.responseCode should be(200)
          }
          "the group is local_group3" in {
            val indexManager = indexManagerWithHeader(
              basicAuthClient("cartman", "user2"),
              "x-ror-current-group" -> "local_group3"
            )

            val result = indexManager.getIndex("test3")
            result.responseCode should be(200)
          }
        }
      }
    }
    "not be seen" when {
      "users, which don't belong to local_group1, request it" in {
        val result = chandlerIndexManager.getIndex("test3")
        result.responseCode should be(404)
      }
    }
  }

  "Test4 index" can {
    "be seen" when {
      "users, which belong to local_group2, request it" in {
        val result = morganIndexManager.getIndex("test4")
        result.responseCode should be(200)
      }
    }
    "not be seen" when {
      "users, which don't belong to local_group2, request it" in {
        val result = cartmanIndexManager.getIndex("test4")
        result.responseCode should be(404)
      }
    }
  }

  "Test 5 index" can {
    "be seen" when {
      "god is worshiped in europe" in {
        val result = jesusIndexManager.getIndex("test5")
        result.responseCode should be(200)
      }
    }

    "Test6 index" can {
      "be seen" when {
        "users, which belong to group2 AND group3, request it" in {
          val result = morganIndexManager.getIndex("test6")
          result.responseCode should be(200)
        }
      }
      "not be seen" when {
        "users, which don't belong to group2 AND group3, request it" in {
          val result = cartmanIndexManager.getIndex("test6")
          result.responseCode should be(404)
        }
      }
    }

    "not be seen" when {
      "god is not worshiped in europe" in {
        val result = allahIndexManager.getIndex("test5")
        result.responseCode should be(403)
      }
    }
  }
}

object LdapIntegrationSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager.createDoc("test1", 1, ujson.read("""{"hello":"world"}""")).force()
    documentManager.createDoc("test2", 1, ujson.read("""{"hello":"world"}""")).force()
    documentManager.createDoc("test3", 1, ujson.read("""{"hello":"world"}""")).force()
    documentManager.createDoc("test4", 1, ujson.read("""{"hello":"world"}""")).force()
    documentManager.createDoc("test5", 1, ujson.read("""{"hello":"world"}""")).force()
    documentManager.createDoc("test6", 1, ujson.read("""{"hello":"world"}""")).force()
  }
}