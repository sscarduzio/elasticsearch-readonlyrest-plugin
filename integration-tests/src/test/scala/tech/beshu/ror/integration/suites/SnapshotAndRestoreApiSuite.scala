package tech.beshu.ror.integration.suites

import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SnapshotManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait SnapshotAndRestoreApiSuite
  extends WordSpec
    with BaseSingleNodeEsClusterTest
    with Eventually
    with IntegrationPatience
    with BeforeAndAfterEach
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/snapshot_and_restore_api/readonlyrest.yml"

  override def nodeDataInitializer = Some(SnapshotAndRestoreApiSuite.nodeDataInitializer())

  private lazy val adminSnapshotManager = new SnapshotManager(basicAuthClient("admin", "container"))
  private lazy val dev1SnapshotManager = new SnapshotManager(basicAuthClient("dev1", "test"))
  private lazy val dev2SnapshotManager = new SnapshotManager(basicAuthClient("dev2", "test"))

  "Snapshot repository management API" when {
    "user creates a repository" should {
      "allow him to do so" when {
        "block doesn't contains 'repositories' rule" in {
          val result = dev1SnapshotManager.putRepository("dev1-repo-1")

          result.responseCode should be (200)
        }
        "user has access to repository name" in {
          val result = dev2SnapshotManager.putRepository("dev2-repo-1")

          result.responseCode should be (200)
        }
      }
      "not allow him to do so" when {
        "user had no access to repository name" in {
          val result = dev2SnapshotManager.putRepository("dev1-repo-1")

          result.responseCode should be (403)
        }
      }
    }
    "user verifies a repository" should {
      "allow him to do so" when {
        "block doesn't contains 'repositories' rule" in {
          adminSnapshotManager.putRepository("dev1-repo-2").force()

          val result = dev1SnapshotManager.verifyRepository("dev1-repo-2")

          result.responseCode should be (200)
        }
        "user has access to repository name" in {
          adminSnapshotManager.putRepository("dev2-repo-2").force()

          val result = dev2SnapshotManager.verifyRepository("dev2-repo-2")

          result.responseCode should be (200)
        }
      }
      "not allow him to do so" when {
        "user had no access to repository name" in {
          adminSnapshotManager.putRepository("dev1-repo-2").force()

          val result = dev2SnapshotManager.verifyRepository("dev1-repo-2")

          result.responseCode should be (403)
        }
      }
    }
    "user cleans up a repository" should {
      "allow him to do so" when {
        "block doesn't contains 'repositories' rule" in {
          adminSnapshotManager.putRepository("dev1-repo-2").force()

          val result = dev1SnapshotManager.cleanUpRepository("dev1-repo-2")

          result.responseCode should be (200)
        }
        "user has access to repository name" in {
          adminSnapshotManager.putRepository("dev2-repo-2").force()

          val result = dev2SnapshotManager.cleanUpRepository("dev2-repo-2")

          result.responseCode should be (200)
        }
      }
      "not allow him to do so" when {
        "user had no access to repository name" in {
          adminSnapshotManager.putRepository("dev1-repo-2").force()

          val result = dev2SnapshotManager.cleanUpRepository("dev1-repo-2")

          result.responseCode should be (403)
        }
      }
    }
  }

  override protected def beforeEach(): Unit = {
    adminSnapshotManager.deleteAllSnapshots().force()
    super.beforeEach()
  }
}

object SnapshotAndRestoreApiSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager.createFirstDoc("index1", ujson.read("""{"hello":"world"}""")).force()
    documentManager.createFirstDoc("index2", ujson.read("""{"hello":"world"}""")).force()
  }
}