package tech.beshu.ror.integration.suites

import org.scalatest.Matchers._
import org.scalatest.{BeforeAndAfterEach, WordSpec}
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.EsContainerCreator
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager}

trait IndexAliasesManagementSuite
  extends WordSpec
    with BaseSingleNodeEsClusterTest
    with BeforeAndAfterEach
    with ESVersionSupport {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/aliases/readonlyrest.yml"

  private lazy val adminDocumentManager = new DocumentManager(basicAuthClient("admin", "container"), targetEs.esVersion)
  private lazy val adminIndexManager = new IndexManager(basicAuthClient("admin", "container"))
  private lazy val dev1IndexManager = new IndexManager(basicAuthClient("dev1", "test"))
  private lazy val dev4IndexManager = new IndexManager(basicAuthClient("dev4", "test"))

  "Add index alias API" should {
    "be allowed to use" when {
      "there is no indices rule in block" in {
        adminDocumentManager.createFirstDoc("index", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev1-0001",  exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()

        val result = adminIndexManager.createAliasOf("index", "admin-alias")

        result.responseCode should be (200)
      }
      "user has access to both: index pattern and alias name" when {
        "an index of the pattern exists" in {
          adminDocumentManager.createFirstDoc("index", exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev1-0001",  exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()

          val result = dev1IndexManager.createAliasOf("dev1-000*", "dev1")

          result.responseCode should be(200)
        }
        "no index of the pattern exists" in {
          adminDocumentManager.createFirstDoc("index", exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev1-0001",  exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()

          val result = dev1IndexManager.createAliasOf("dev1-0000*", "dev1")

          result.responseCode should be(404)
        }
      }
    }
    "not be allowed to use" when {
      "user has no access to given at least one index pattern" in {
        adminDocumentManager.createFirstDoc("index", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev1-0001",  exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()

        val result = dev1IndexManager.createAliasOf("dev1-000*", "dev2")

        result.responseCode should be (403)
      }
      "user has no access to alias name" in {
        adminDocumentManager.createFirstDoc("index", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev1-0001",  exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()

        val result = dev1IndexManager.createAliasOf("dev2-000*", "dev1")

        result.responseCode should be (403)
      }
    }
  }

  "Delete index alias API" should {
    "be allowed to use" when {
      "there is no indices rule in block" in {
        adminDocumentManager.createFirstDoc("index", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev1-0001",  exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()
        adminIndexManager.createAliasOf("index", "admin-alias").force()

        val result = adminIndexManager.deleteAliasOf("index", "admin-alias")

        result.responseCode should be (200)
      }
      "user has access to both: index pattern and alias name" when {
        "an index of the pattern exists" in {
          adminDocumentManager.createFirstDoc("index", exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev1-0001",  exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()
          adminIndexManager.createAliasOf("dev1-000*", "dev1").force()

          val result = dev1IndexManager.deleteAliasOf("dev1-000*", "dev1")

          result.responseCode should be(200)
        }
        "no index of the pattern exists" in {
          adminDocumentManager.createFirstDoc("index", exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev1-0001",  exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()

          val result = dev1IndexManager.deleteAliasOf("dev1-0000*", "dev1")

          result.responseCode should be(404)
        }
        "no alias exists" in {
          adminDocumentManager.createFirstDoc("index", exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev1-0001",  exampleDocument).force()
          adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()

          val result = dev1IndexManager.deleteAliasOf("dev1-0001", "dev1-000x")

          result.responseCode should be(404)
        }
      }
    }
    "not be allowed to use" when {
      "user has no access to given at least one index pattern" in {
        adminDocumentManager.createFirstDoc("index", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev1-0001",  exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()
        adminIndexManager.createAliasOf("dev1-000*", "dev2").force()

        val result = dev1IndexManager.deleteAliasOf("dev1-000*", "dev2")

        result.responseCode should be (403)
      }
      "user has no access to alias name" in {
        adminDocumentManager.createFirstDoc("index", exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev1-0001",  exampleDocument).force()
        adminDocumentManager.createFirstDoc("dev2-0001", exampleDocument).force()
        adminIndexManager.createAliasOf("dev2-000*", "dev1").force()

        val result = dev1IndexManager.deleteAliasOf("dev2-000*", "dev1")

        result.responseCode should be (403)
      }
    }
  }

  "Exist index alias API" should {
    "return all aliases" when {
      "there is no indices rule in block" in {
        dev4IndexManager.getAlias()
      }
    }
    "return aliases" which {
      "names are allowed by indices rule" which {
        "indices, they are related to, are allowed by indices rule" in {

        }
      }
    }
  }

  override protected def beforeEach(): Unit = {
    adminIndexManager.removeAll.force()
  }

  private def exampleDocument = ujson.read("""{"hello":"world"}""")
}
