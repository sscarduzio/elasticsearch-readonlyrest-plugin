package tech.beshu.ror.integration.suites

import monix.execution.atomic.Atomic
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}
import tech.beshu.ror.integration.suites.SnapshotAndRestoreApiSuite.RepositoryNameGenerator
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
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev1-repo")
          val result = dev1SnapshotManager.putRepository(uniqueRepositoryName)

          result.responseCode should be (200)
        }
        "user has access to repository name" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev2-repo")
          val result = dev2SnapshotManager.putRepository(uniqueRepositoryName)

          result.responseCode should be (200)
        }
      }
      "not allow him to do so" when {
        "user has no access to repository name" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev1-repo")
          val result = dev2SnapshotManager.putRepository(uniqueRepositoryName)

          result.responseCode should be (403)
        }
      }
    }
    "user deletes a repository" should {
      "allow him to do so" when {
        "block doesn't contains 'repositories' rule" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev1-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev1SnapshotManager.deleteRepository(uniqueRepositoryName)

          result.responseCode should be (200)
        }
        "user has access to repository name" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev2SnapshotManager.deleteRepository(uniqueRepositoryName)

          result.responseCode should be (200)
        }
        "user has access to wider repository pattern name than the passed one" in {
          adminSnapshotManager.putRepository(RepositoryNameGenerator.next("dev2-repo-delete")).force()
          adminSnapshotManager.putRepository(RepositoryNameGenerator.next("dev2-repo-delete")).force()

          val result = dev2SnapshotManager.deleteRepository("dev2-repo-delete*")

          result.responseCode should be (200)

          val verificationResult = adminSnapshotManager.getRepository("dev2-repo-delete*")
          verificationResult.repositories.keys.toList should be (List.empty)
        }
        "user has access to narrowed repository pattern name than the passed one" in {
          adminSnapshotManager.putRepository(RepositoryNameGenerator.next("dev2-repo-delete")).force()
          val notAccessibleRepoName = RepositoryNameGenerator.next("dev2-forbid-delete")
          adminSnapshotManager.putRepository(notAccessibleRepoName).force()

          val result = dev2SnapshotManager.deleteRepository("dev2*")

          result.responseCode should be (200)

          val verificationResult = adminSnapshotManager.getRepository("dev2*")
          verificationResult.repositories.keys.toList should be (List(notAccessibleRepoName))
        }
      }
      "not allow him to do so" when {
        "user has no access to repository name" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev1-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev2SnapshotManager.deleteRepository(uniqueRepositoryName)

          result.responseCode should be (403)
        }
      }
    }
    "user verifies a repository" should {
      "allow him to do so" when {
        "block doesn't contains 'repositories' rule" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev1-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev1SnapshotManager.verifyRepository(uniqueRepositoryName)

          result.responseCode should be (200)
        }
        "user has access to repository name" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev2SnapshotManager.verifyRepository(uniqueRepositoryName)

          result.responseCode should be (200)
        }
      }
      "not allow him to do so" when {
        "user has no access to repository name" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev1-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev2SnapshotManager.verifyRepository(uniqueRepositoryName)

          result.responseCode should be (403)
        }
      }
    }
    "user cleans up a repository" should {
      "allow him to do so" when {
        "block doesn't contains 'repositories' rule" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev1-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev1SnapshotManager.cleanUpRepository(uniqueRepositoryName)

          result.responseCode should be (200)
        }
        "user has access to repository name" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev2SnapshotManager.cleanUpRepository(uniqueRepositoryName)

          result.responseCode should be (200)
        }
      }
      "not allow him to do so" when {
        "user has no access to repository name" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev1-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev2SnapshotManager.cleanUpRepository(uniqueRepositoryName)

          result.responseCode should be (403)
        }
      }
    }
    "user gets repositories" should {
      "allow him to do so" when {
        "block doesn't contains 'repositories' rule" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev1-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev1SnapshotManager.getRepository(uniqueRepositoryName)

          result.responseCode should be (200)
          result.repositories.keys.toList should be (List(uniqueRepositoryName))
        }
        "all repositories are requested" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()
          adminSnapshotManager.putRepository(RepositoryNameGenerator.next("dev1-repo")).force()
          adminSnapshotManager.putRepository(RepositoryNameGenerator.next("dev3-repo")).force()

          val result = dev2SnapshotManager.getAllRepositories()

          result.responseCode should be (200)
          all(result.repositories.keys) should startWith ("dev2-repo")
        }
        "user has access to repository name" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev2SnapshotManager.getRepository(uniqueRepositoryName)

          result.responseCode should be (200)
          result.repositories.keys.toList should be (List(uniqueRepositoryName))
        }
        "user has access to repository pattern" in {
          val uniqueRepositoryName1 = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName1).force()
          val uniqueRepositoryName2 = RepositoryNameGenerator.next("dev2-test")
          adminSnapshotManager.putRepository(uniqueRepositoryName2).force()

          val result = dev2SnapshotManager.getRepository("dev2*")

          result.responseCode should be (200)
          all(result.repositories.keys) should startWith ("dev2-repo-")
        }
      }
      "return empty list" when {
        "asked repository name pattern have to be narrowed" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev2-forbidden")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev2SnapshotManager.getRepository("dev2*")

          result.responseCode should be (200)
          result.repositories.keys.toList should be (List.empty)
        }
      }
      "return 404" when {
        "user has no access to repository name" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev1SnapshotManager.getRepository("dev2-repo-5")

          result.responseCode should be (404)
        }
        "there are no such repository" in {
          val result = dev2SnapshotManager.getRepository(RepositoryNameGenerator.next("dev2-repo"))

          result.responseCode should be (404)
        }
      }
      "not allow him to do so" when {
        "user has no access to repository name pattern" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev3-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev2SnapshotManager.getRepository("dev3*")

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

  private object RepositoryNameGenerator {
    private val uniquePart = Atomic(0)
    def next(prefix: String): String = s"$prefix-${uniquePart.incrementAndGet()}"
  }
}