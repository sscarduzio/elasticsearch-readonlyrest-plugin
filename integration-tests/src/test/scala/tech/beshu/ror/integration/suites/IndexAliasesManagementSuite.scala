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

import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, SingletonPluginTestSupport}
import tech.beshu.ror.utils.elasticsearch.IndexManager.AliasAction.{Add, Delete}
import tech.beshu.ror.utils.elasticsearch.{CatManager, DocumentManager, IndexManager}
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

class IndexAliasesManagementSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with BeforeAndAfterEach
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/aliases/readonlyrest.yml"

  private lazy val adminDocumentManager = new DocumentManager(basicAuthClient("admin", "container"), esVersionUsed)
  private lazy val adminIndexManager = new IndexManager(basicAuthClient("admin", "container"), esVersionUsed)
  private lazy val adminCatManager = new CatManager(basicAuthClient("admin", "container"), esVersionUsed)
  private lazy val dev1IndexManager = new IndexManager(basicAuthClient("dev1", "test"), esVersionUsed)
  private lazy val dev3IndexManager = new IndexManager(basicAuthClient("dev3", "test"), esVersionUsed)

  "Add index alias API" should {
    "be allowed to be used" when {
      "there is no indices rule in block" in {
        adminDocumentManager.createFirstDoc("index", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev1-0001", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()

        val result = adminIndexManager.createAliasOf("index", "admin-alias")

        result should have statusCode 200
        val allAliasesResponse = adminIndexManager.getAliases
        allAliasesResponse should have statusCode 200
        allAliasesResponse.aliasesOfIndices("index") should be(Set("admin-alias"))
      }
      "user has access to both: index pattern and alias name" when {
        "an index of the pattern exists" in {
          adminDocumentManager.createFirstDoc("index", exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev1-0001", exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()

          val result = dev1IndexManager.createAliasOf("dev1-000*", "dev1")

          result should have statusCode 200
          val allAliasesResponse = adminIndexManager.getAliases
          allAliasesResponse should have statusCode 200
          allAliasesResponse.aliasesOfIndices("dev1-0001") should be(Set("dev1"))
        }
        "no index of the pattern exists" in {
          adminDocumentManager.createFirstDoc("index", exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev1-0001", exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()

          val result = dev1IndexManager.createAliasOf("dev1-0000*", "dev1")

          result should have statusCode 404
        }
      }
    }
    "not be allowed to be used" when {
      "user has no access to given at least one index pattern" in {
        adminDocumentManager.createFirstDoc("index", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev1-0001", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()

        val result = dev1IndexManager.createAliasOf("dev1-000*", "dev2")

        result should have statusCode 403
      }
      "user has no access to alias name" in {
        adminDocumentManager.createFirstDoc("index", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev1-0001", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()

        val result = dev1IndexManager.createAliasOf("dev2-000*", "dev1")

        result should have statusCode 403
      }
    }
  }

  "Delete index alias API" should {
    "be allowed to be used" when {
      "there is no indices rule in block" in {
        adminDocumentManager.createFirstDoc("index", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev1-0001", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()
        adminIndexManager.createAliasOf("index", "admin-alias").force()

        val result = adminIndexManager.deleteAliasOf("index", "admin-alias")

        result should have statusCode 200
        val allAliasesResponse = adminCatManager.aliases()
        allAliasesResponse should have statusCode 200
        allAliasesResponse.results.map(_.obj("alias").str) should not contain "admin-alias"
      }
      "user has access to both: index pattern and alias name" when {
        "an index of the pattern exists" in {
          adminDocumentManager.createFirstDoc("index", exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev1-0001", exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()
          adminIndexManager.createAliasOf("dev1-000*", "dev1").force()

          val result = dev1IndexManager.deleteAliasOf("dev1-000*", "dev1")

          result should have statusCode 200

          val allAliasesResponse = adminCatManager.aliases()
          allAliasesResponse should have statusCode 200
          allAliasesResponse.results.map(_.obj("alias").str) should not contain "dev1"
        }
        "no index of the pattern exists" in {
          adminDocumentManager.createFirstDoc("index", exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev1-0001", exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()

          val result = dev1IndexManager.deleteAliasOf("dev1-0000*", "dev1")

          result should have statusCode 404
        }
        "no alias exists" in {
          adminDocumentManager.createFirstDoc("index", exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev1-0001", exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()

          val result = dev1IndexManager.deleteAliasOf("dev1-0001", "dev1-000x")

          result should have statusCode 404
        }
      }
    }
    "not be allowed to be used" when {
      "user has no access to given at least one index pattern" in {
        adminDocumentManager.createFirstDoc("index", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev1-0001", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()
        adminIndexManager.createAliasOf("dev1-000*", "dev2").force()

        val result = dev1IndexManager.deleteAliasOf("dev1-000*", "dev2")

        result should have statusCode 403
      }
      "user has no access to alias name" in {
        adminDocumentManager.createFirstDoc("index", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev1-0001", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()
        adminIndexManager.createAliasOf("dev2-000*", "dev1").force()

        val result = dev1IndexManager.deleteAliasOf("dev2-000*", "dev1")

        result should have statusCode 403
      }
    }
  }

  "Update index alias API" should {
    "be allowed to be used" when {
      "user has access to all indices and aliases from actions" in {
        adminDocumentManager.createFirstDoc("dev3-0001", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev3-0002", exampleDocument).force()

        val result = dev3IndexManager.updateAliases(
          Add("dev3-0001", "dev3"),
          Add("dev3-0002", "dev3")
        )

        result should have statusCode 200
        val allAliasesResponse = adminIndexManager.getAliases
        allAliasesResponse should have statusCode 200
        allAliasesResponse.aliasesOfIndices("dev3-0001") should be(Set("dev3"))
        allAliasesResponse.aliasesOfIndices("dev3-0002") should be(Set("dev3"))
      }
    }
    "be not allowed to be used" when {
      "user doesn't have access to at least one index from actions" in {
        adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev3-0001", exampleDocument).force()
        adminIndexManager.createAliasOf("dev3-0001", "dev3").force()

        val result = dev3IndexManager.updateAliases(
          Delete("dev3-0001", "dev3"),
          Add("dev2-0001", "dev3")
        )

        result should have statusCode 403
        val allAliasesResponse = adminIndexManager.getAliases
        allAliasesResponse should have statusCode 200
        allAliasesResponse.aliasesOfIndices("dev3-0001") should be(Set("dev3"))
      }
      "user doesn't have access to at least one alias from actions" in {
        adminDocumentManager.createFirstDoc("dev3-0001", exampleDocument).force()
        adminIndexManager.createAliasOf("dev3-0001", "dev3").force()
        adminDocumentManager.createFirstDoc("dev3-0002", exampleDocument).force()

        val result = dev3IndexManager.updateAliases(
          Delete("dev3-0001", "dev3"),
          Add("dev3-0002", "dev2")
        )

        result should have statusCode 403
        val allAliasesResponse = adminIndexManager.getAliases
        allAliasesResponse should have statusCode 200
        allAliasesResponse.aliasesOfIndices("dev3-0001") should be(Set("dev3"))
      }
    }
  }

  override protected def beforeEach(): Unit = {
    adminIndexManager.removeAllIndices().force()
    adminIndexManager.removeAllAliases()
  }

  private def exampleDocument = ujson.read("""{"hello":"world"}""")
}
