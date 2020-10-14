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
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}
import tech.beshu.ror.integration.suites.SnapshotAndRestoreApiSuite.{RepositoryNameGenerator, SnapshotNameGenerator}
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, SnapshotManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait SnapshotAndRestoreApiSuite
  extends WordSpec
    with BaseSingleNodeEsClusterTest
    with Eventually
    with IntegrationPatience
    with BeforeAndAfterEach
    with Matchers
    with ESVersionSupport {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/snapshot_and_restore_api/readonlyrest.yml"

  override def nodeDataInitializer = Some(SnapshotAndRestoreApiSuite.nodeDataInitializer())

  private lazy val adminSnapshotManager = new SnapshotManager(basicAuthClient("admin", "container"))
  private lazy val adminIndexManager = new IndexManager(basicAuthClient("admin", "container"))
  private lazy val dev1SnapshotManager = new SnapshotManager(basicAuthClient("dev1", "test"))
  private lazy val dev2SnapshotManager = new SnapshotManager(basicAuthClient("dev2", "test"))
  private lazy val dev3SnapshotManager = new SnapshotManager(basicAuthClient("dev3", "test"))

  "Snapshot repository management API" when {
    "user creates a repository" should {
      "allow him to do so" when {
        "block doesn't contain 'repositories' rule" in {
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
        "block doesn't contains 'repositories' rule" excludeES(allEs5x, allEs6x, allEs7xBelowEs74x) in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev1-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev1SnapshotManager.cleanUpRepository(uniqueRepositoryName)

          result.responseCode should be (200)
        }
        "user has access to repository name" excludeES(allEs5x, allEs6x, allEs7xBelowEs74x) in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev2SnapshotManager.cleanUpRepository(uniqueRepositoryName)

          result.responseCode should be (200)
        }
      }
      "not allow him to do so" when {
        "user has no access to repository name" excludeES(allEs5x, allEs6x, allEs7xBelowEs74x) in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev1-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev2SnapshotManager.cleanUpRepository(uniqueRepositoryName)

          result.responseCode should be (403)
        }
      }
    }
    "user gets repositories" should {
      "allow him to do so" when {
        "block doesn't contain 'repositories' rule" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev1-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()

          val result = dev1SnapshotManager.getRepository(uniqueRepositoryName)

          result.responseCode should be (200)
          result.repositories.keys.toList should be (List(uniqueRepositoryName))
        }
        "all repositories are requested (calling _all explicitly)" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()
          adminSnapshotManager.putRepository(RepositoryNameGenerator.next("dev1-repo")).force()
          adminSnapshotManager.putRepository(RepositoryNameGenerator.next("dev3-repo")).force()

          val result = dev2SnapshotManager.getRepository("_all")

          result.responseCode should be (200)
          all(result.repositories.keys) should startWith ("dev2-repo")
        }
        "all repositories are requested (without calling _all explicitly)" in {
          val uniqueRepositoryName = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName).force()
          adminSnapshotManager.putRepository(RepositoryNameGenerator.next("dev1-repo")).force()
          adminSnapshotManager.putRepository(RepositoryNameGenerator.next("dev3-repo")).force()

          val result = dev2SnapshotManager.getAllRepositories

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
        "user has access to full repository pattern" in {
          val uniqueRepositoryName1 = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName1).force()
          val uniqueRepositoryName2 = RepositoryNameGenerator.next("dev2-test")
          adminSnapshotManager.putRepository(uniqueRepositoryName2).force()

          val result = dev2SnapshotManager.getRepository("dev2*")

          result.responseCode should be (200)
          all(result.repositories.keys) should startWith ("dev2-repo-")
        }
        "requested repository pattern has to be narrowed" in {
          val uniqueRepositoryName1 = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(uniqueRepositoryName1).force()
          val uniqueRepositoryName2 = RepositoryNameGenerator.next("dev2-test")
          adminSnapshotManager.putRepository(uniqueRepositoryName2).force()

          val result = dev2SnapshotManager.getRepository("dev2-repo-*")

          result.responseCode should be (200)
          all(result.repositories.keys) should startWith ("dev2-repo-")
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

  "Snapshot management API" when {
    "user creates a snapshot" should {
      "allow him to do so" when {
        "block doesn't contain 'repositories' and 'snapshots' rules" in {
          val repositoryName = RepositoryNameGenerator.next("dev1")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName = SnapshotNameGenerator.next("dev1")
          val response = dev1SnapshotManager.putSnapshot(repositoryName, snapshotName, "index1")

          response.responseCode should be (200)
        }
        "user has access to repository and snapshot name and all related indices" in {
          val repositoryName = RepositoryNameGenerator.next("dev2-repo-")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName = SnapshotNameGenerator.next("dev2-snap-")
          val response = dev2SnapshotManager.putSnapshot(repositoryName, snapshotName, "index2")

          response.responseCode should be (200)
        }
        "user has access to repository and snapshot name and the index pattern" in {
          val repositoryName = RepositoryNameGenerator.next("dev2-repo-")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName = SnapshotNameGenerator.next("dev2-snap-")
          val response = dev2SnapshotManager.putSnapshot(repositoryName, snapshotName, "index2*")

          response.responseCode should be (200)
        }
      }
      "not allow him to do so" when {
        "user has no access to repository" in {
          val repositoryName = RepositoryNameGenerator.next("dev1-repo-")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName = SnapshotNameGenerator.next("dev2-snap-")
          val response = dev2SnapshotManager.putSnapshot(repositoryName, snapshotName, "index2")

          response.responseCode should be (403)
        }
        "user has no access to snapshot" in {
          val repositoryName = RepositoryNameGenerator.next("dev2-repo-")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName = SnapshotNameGenerator.next("dev1-snap-")
          val response = dev2SnapshotManager.putSnapshot(repositoryName, snapshotName, "index2")

          response.responseCode should be (403)
        }
        "user has no access to at least one index" in {
          val repositoryName = RepositoryNameGenerator.next("dev2-repo-")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName = SnapshotNameGenerator.next("dev2-snap-")
          val response = dev2SnapshotManager.putSnapshot(repositoryName, snapshotName, "index2", "index1")

          response.responseCode should be (403)
        }
        "user has access to narrowed index pattern than the one in request" in {
          val repositoryName = RepositoryNameGenerator.next("dev2-repo-")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName = SnapshotNameGenerator.next("dev2-snap-")
          val response = dev2SnapshotManager.putSnapshot(repositoryName, snapshotName, "index*")

          response.responseCode should be (403)

        }
      }
    }
    "user gets snapshots" should {
      "allow him to do so" when {
        "block doesn't contain repositories, snapshots, indices rules" in {
          val repositoryName = RepositoryNameGenerator.next("dev3-repo-")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName1 = SnapshotNameGenerator.next("dev3-snap-")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index*").force()

          val snapshotName2 = SnapshotNameGenerator.next("dev3-snap-")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index1").force()

          val result = dev3SnapshotManager.getAllSnapshotsOf(repositoryName)

          result.responseCode should be (200)
          result.snapshots.map(_ ("snapshot").str) should be (List(snapshotName1, snapshotName2))
        }
        "user has access to repository name" when {
          "has also access to snapshot name" in {
            val repositoryName = RepositoryNameGenerator.next("dev2-repo")
            adminSnapshotManager.putRepository(repositoryName).force()

            val snapshotName1 = SnapshotNameGenerator.next("dev2-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index2").force()

            val snapshotName2 = SnapshotNameGenerator.next("dev1-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index1").force()

            val result = dev2SnapshotManager.getSnapshotsOf(repositoryName, snapshotName1)

            result.responseCode should be (200)
            result.snapshots.map(_ ("snapshot").str) should be (List(snapshotName1))
          }
          "has also access to one of requested snapshot names" in {
            val repositoryName = RepositoryNameGenerator.next("dev2-repo")
            adminSnapshotManager.putRepository(repositoryName).force()

            val snapshotName1 = SnapshotNameGenerator.next("dev2-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index2").force()

            val snapshotName2 = SnapshotNameGenerator.next("dev1-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index1").force()

            val result = dev2SnapshotManager.getSnapshotsOf(repositoryName, snapshotName1, snapshotName2)

            result.responseCode should be (200)
            result.snapshots.map(_ ("snapshot").str) should be (List(snapshotName1))
          }
          "snapshot pattern has to be narrowed" in {
            val repositoryName = RepositoryNameGenerator.next("dev2-repo")
            adminSnapshotManager.putRepository(repositoryName).force()

            val snapshotName1 = SnapshotNameGenerator.next("dev2-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index2").force()

            val snapshotName2 = SnapshotNameGenerator.next("dev2-forbidden")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index2").force()

            val result = dev2SnapshotManager.getSnapshotsOf(repositoryName, "dev2*")

            result.responseCode should be (200)
            result.snapshots.map(_ ("snapshot").str) should be (List(snapshotName1))
          }
          "snapshot pattern doesn't have to be narrowed" in {
            val repositoryName = RepositoryNameGenerator.next("dev2-repo")
            adminSnapshotManager.putRepository(repositoryName).force()

            val snapshotName1 = SnapshotNameGenerator.next("dev2-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index2").force()

            val snapshotName2 = SnapshotNameGenerator.next("dev2-forbidden")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index2").force()

            val result = dev2SnapshotManager.getSnapshotsOf(repositoryName, "dev2-snap-*")

            result.responseCode should be (200)
            result.snapshots.map(_ ("snapshot").str) should be (List(snapshotName1))
          }
          "one of requested snapshot patterns is allowed" in {
            val repositoryName = RepositoryNameGenerator.next("dev2-repo")
            adminSnapshotManager.putRepository(repositoryName).force()

            val snapshotName1 = SnapshotNameGenerator.next("dev2-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index2").force()

            val snapshotName2 = SnapshotNameGenerator.next("dev2-forbidden")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index2").force()

            val result = dev2SnapshotManager.getSnapshotsOf(repositoryName, "dev2*", "dev1*")

            result.responseCode should be (200)
            result.snapshots.map(_ ("snapshot").str) should be (List(snapshotName1))
          }
        }
      }
      "not allow him to do so" when {
        "user has no access to repository" in {
          val repositoryName = RepositoryNameGenerator.next("dev1-repo")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName1 = SnapshotNameGenerator.next("dev1-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index2").force()

          val snapshotName2 = SnapshotNameGenerator.next("dev1-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index2").force()

          val result = dev2SnapshotManager.getSnapshotsOf(repositoryName, "dev1*")

          result.responseCode should be (403)
        }
        "user has no access to snapshot" in {
          val repositoryName = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName1 = SnapshotNameGenerator.next("dev1-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index2").force()

          val snapshotName2 = SnapshotNameGenerator.next("dev2-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index2").force()

          val result = dev2SnapshotManager.getSnapshotsOf(repositoryName, snapshotName1)

          result.responseCode should be (403)
        }
        "user has no access to snapshot pattern" in {
          val repositoryName = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName1 = SnapshotNameGenerator.next("dev1-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index2").force()

          val snapshotName2 = SnapshotNameGenerator.next("dev1-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index2").force()

          val result = dev2SnapshotManager.getSnapshotsOf(repositoryName, "dev1*")

          result.responseCode should be (403)
        }
      }
    }
    "user gets snapshot statuses" should {
      "allow him to do so" when {
        "user has access to repository name" when {
          "has also access to snapshot name" in {
            val repositoryName = RepositoryNameGenerator.next("dev2-repo")
            adminSnapshotManager.putRepository(repositoryName).force()

            val snapshotName1 = SnapshotNameGenerator.next("dev2-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index2").force()

            val snapshotName2 = SnapshotNameGenerator.next("dev1-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index1").force()

            val result = dev2SnapshotManager.getAllSnapshotStatusesOf(repositoryName, snapshotName1)

            result.responseCode should be (200)
            result.snapshots.map(_ ("snapshot").str) should be (List(snapshotName1))
          }
          "has also access to one of requested snapshot names" in {
            val repositoryName = RepositoryNameGenerator.next("dev2-repo")
            adminSnapshotManager.putRepository(repositoryName).force()

            val snapshotName1 = SnapshotNameGenerator.next("dev2-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index2").force()

            val snapshotName2 = SnapshotNameGenerator.next("dev1-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index1").force()

            val result = dev2SnapshotManager.getAllSnapshotStatusesOf(repositoryName, snapshotName1, snapshotName2)

            result.responseCode should be (200)
            result.snapshots.map(_ ("snapshot").str) should be (List(snapshotName1))
          }
        }
      }
      "not allow him to do so" when {
        "user has no access to repository" in {
          val repositoryName = RepositoryNameGenerator.next("dev1-repo")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName1 = SnapshotNameGenerator.next("dev1-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index2").force()

          val snapshotName2 = SnapshotNameGenerator.next("dev1-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index2").force()

          val result = dev2SnapshotManager.getAllSnapshotStatusesOf(repositoryName, "dev1*")

          result.responseCode should be (403)
        }
        "user has no access to snapshot" in {
          val repositoryName = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName1 = SnapshotNameGenerator.next("dev1-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index2").force()

          val snapshotName2 = SnapshotNameGenerator.next("dev2-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index2").force()

          val result = dev2SnapshotManager.getAllSnapshotStatusesOf(repositoryName, snapshotName1)

          result.responseCode should be (403)
        }
      }
    }
    "user deletes snapshots" should {
      "be able to do so" when {
        "block doesn't contain repositories, snapshots, indices rules" when {
          "one snapshot is being removed" in {
            val repositoryName = RepositoryNameGenerator.next("dev3-repo")
            adminSnapshotManager.putRepository(repositoryName).force()

            val snapshotName = SnapshotNameGenerator.next("dev3-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName, "index1").force()

            val result = dev3SnapshotManager.deleteSnapshotsOf(repositoryName, snapshotName)

            result.responseCode should be (200)
            val verification = adminSnapshotManager.getAllSnapshotsOf(repositoryName)
            verification.snapshots.map(_("snapshot").str) should be (List.empty)
          }
          "many snapshots are being removed" excludeES(allEs5x, allEs6x, allEs7xBelowEs78x) in {
            val repositoryName = RepositoryNameGenerator.next("dev3-repo")
            adminSnapshotManager.putRepository(repositoryName).force()

            val snapshotName1 = SnapshotNameGenerator.next("dev3-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index*").force()

            val snapshotName2 = SnapshotNameGenerator.next("dev3-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index1").force()

            val result = dev3SnapshotManager.deleteSnapshotsOf(repositoryName, snapshotName1, snapshotName2)

            result.responseCode should be (200)
            val verification = adminSnapshotManager.getAllSnapshotsOf(repositoryName)
            verification.snapshots.map(_("snapshot").str) should be (List.empty)
          }
        }
        "user has access to repository name" when {
          "user has access to all requested snapshots" when {
            "one snapshot is being removed" in {
              val repositoryName = RepositoryNameGenerator.next("dev2-repo")
              adminSnapshotManager.putRepository(repositoryName).force()

              val snapshotName = SnapshotNameGenerator.next("dev2-snap")
              adminSnapshotManager.putSnapshot(repositoryName, snapshotName, "index2").force()

              val result = dev2SnapshotManager.deleteSnapshotsOf(repositoryName, snapshotName)

              result.responseCode should be(200)
              val verification = adminSnapshotManager.getAllSnapshotsOf(repositoryName)
              verification.snapshots.map(_ ("snapshot").str) should be(List.empty)
            }
            "many snapshots are being removed" excludeES (allEs5x, allEs6x, allEs7xBelowEs78x) in {
              val repositoryName = RepositoryNameGenerator.next("dev2-repo")
              adminSnapshotManager.putRepository(repositoryName).force()

              val snapshotName1 = SnapshotNameGenerator.next("dev2-snap")
              adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index*").force()

              val snapshotName2 = SnapshotNameGenerator.next("dev2-snap")
              adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index1").force()

              val result = dev2SnapshotManager.deleteSnapshotsOf(repositoryName, snapshotName1, snapshotName2)

              result.responseCode should be(200)
              val verification = adminSnapshotManager.getAllSnapshotsOf(repositoryName)
              verification.snapshots.map(_ ("snapshot").str) should be(List.empty)
            }
          }
          "user has access to requested snapshot pattern" excludeES(allEs5x, allEs6x, allEs7xBelowEs78x) in {
            val repositoryName = RepositoryNameGenerator.next("dev2-repo")
            adminSnapshotManager.putRepository(repositoryName).force()

            val snapshotName1 = SnapshotNameGenerator.next("dev2-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index*").force()

            val snapshotName2 = SnapshotNameGenerator.next("dev2-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index1").force()

            val result = dev2SnapshotManager.deleteSnapshotsOf(repositoryName, "dev2-snap-*")

            result.responseCode should be(200)
            val verification = adminSnapshotManager.getAllSnapshotsOf(repositoryName)
            verification.snapshots.map(_ ("snapshot").str) should be(List.empty)
          }
        }
      }
      "not be able to do so" when {
        "user has no access to repository name" in {
          val repositoryName = RepositoryNameGenerator.next("dev3-repo")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName1 = SnapshotNameGenerator.next("dev2-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index*").force()

          val snapshotName2 = SnapshotNameGenerator.next("dev2-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index1").force()

          val result = dev2SnapshotManager.deleteSnapshotsOf(repositoryName, snapshotName1, snapshotName2)

          result.responseCode should be (403)
          val verification = adminSnapshotManager.getAllSnapshotsOf(repositoryName)
          verification.snapshots.map(_("snapshot").str) should be (List(snapshotName1, snapshotName2))
        }
        "user has no access to at least one requested snapshot name" when {
          "one snapshot is being removed" in {
            val repositoryName = RepositoryNameGenerator.next("dev2-repo")
            adminSnapshotManager.putRepository(repositoryName).force()

            val snapshotName = SnapshotNameGenerator.next("dev3-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName, "index1").force()

            val result = dev2SnapshotManager.deleteSnapshotsOf(repositoryName, snapshotName)

            result.responseCode should be(403)
            val verification = adminSnapshotManager.getAllSnapshotsOf(repositoryName)
            verification.snapshots.map(_ ("snapshot").str) should be(List(snapshotName))
          }
          "many snapshots are being removed" excludeES(allEs5x, allEs6x, allEs7xBelowEs78x) in {
            val repositoryName = RepositoryNameGenerator.next("dev2-repo")
            adminSnapshotManager.putRepository(repositoryName).force()

            val snapshotName1 = SnapshotNameGenerator.next("dev2-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index*").force()

            val snapshotName2 = SnapshotNameGenerator.next("dev3-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index1").force()

            val result = dev2SnapshotManager.deleteSnapshotsOf(repositoryName, snapshotName1, snapshotName2)

            result.responseCode should be(403)
            val verification = adminSnapshotManager.getAllSnapshotsOf(repositoryName)
            verification.snapshots.map(_ ("snapshot").str) should be(List(snapshotName1, snapshotName2))
          }
        }
        "user has no access to requested snapshot name pattern" in {
          val repositoryName = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName1 = SnapshotNameGenerator.next("dev2-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index*").force()

          val snapshotName2 = SnapshotNameGenerator.next("dev2-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index1").force()

          val result = dev2SnapshotManager.deleteSnapshotsOf(repositoryName, "dev2*")

          result.responseCode should be (403)
          val verification = adminSnapshotManager.getAllSnapshotsOf(repositoryName)
          verification.snapshots.map(_("snapshot").str) should be (List(snapshotName1, snapshotName2))
        }
      }
    }
    "user restores snapshot" should {
      "be able to do so" when {
        "block doesn't contain repositories, snapshots, indices rules" in {
          val repositoryName = RepositoryNameGenerator.next("dev3-repo")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName1 = SnapshotNameGenerator.next("dev3-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index1").force()

          val snapshotName2 = SnapshotNameGenerator.next("dev3-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName2, "index2").force()

          val result = dev3SnapshotManager.restoreSnapshot(repositoryName, snapshotName1)

          result.responseCode should be (200)
          val verification1 = adminIndexManager.getIndex("restored_index1")
          verification1.responseCode should be (200)
          val verification2 = adminIndexManager.getIndex("restored_index2")
          verification2.responseCode should be (404)
        }
        "user has access to repository and snapshot name" when {
          "all indices from snapshot are restored" in {
            val repositoryName = RepositoryNameGenerator.next("dev2-repo")
            adminSnapshotManager.putRepository(repositoryName).force()

            val snapshotName1 = SnapshotNameGenerator.next("dev2-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index2*").force()

            val result = dev2SnapshotManager.restoreSnapshot(repositoryName, snapshotName1, "index2*")

            result.responseCode should be (200)
            val verification1 = adminIndexManager.getIndex("restored_index1")
            verification1.responseCode should be (404)
            val verification2 = adminIndexManager.getIndex("restored_index2")
            verification2.responseCode should be (200)
          }
          "only one index from snapshot is restored" in {
            val repositoryName = RepositoryNameGenerator.next("dev2-repo")
            adminSnapshotManager.putRepository(repositoryName).force()

            val snapshotName1 = SnapshotNameGenerator.next("dev2-snap")
            adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "*").force()

            val result = dev2SnapshotManager.restoreSnapshot(repositoryName, snapshotName1, "index2")

            result.responseCode should be (200)
            val verification1 = adminIndexManager.getIndex("restored_index1")
            verification1.responseCode should be (404)
            val verification2 = adminIndexManager.getIndex("restored_index2")
            verification2.responseCode should be (200)
          }
        }
      }
      "not be able to do so" when {
        "user has no access to repository name" in {
          val repositoryName = RepositoryNameGenerator.next("dev1-repo")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName1 = SnapshotNameGenerator.next("dev2-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index2*").force()

          val result = dev2SnapshotManager.restoreSnapshot(repositoryName, snapshotName1, "index2*")

          result.responseCode should be (403)
        }
        "user has no access to snapshot name" in {
          val repositoryName = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName1 = SnapshotNameGenerator.next("dev1-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index2*").force()

          val result = dev2SnapshotManager.restoreSnapshot(repositoryName, snapshotName1, "index2*")

          result.responseCode should be (403)
        }
        "user has no access to index name" in {
          val repositoryName = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName1 = SnapshotNameGenerator.next("dev2-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "index1").force()

          val result = dev2SnapshotManager.restoreSnapshot(repositoryName, snapshotName1, "index1")

          result.responseCode should be (403)
        }
        "user has no access to index pattern" in {
          val repositoryName = RepositoryNameGenerator.next("dev2-repo")
          adminSnapshotManager.putRepository(repositoryName).force()

          val snapshotName1 = SnapshotNameGenerator.next("dev2-snap")
          adminSnapshotManager.putSnapshot(repositoryName, snapshotName1, "*").force()

          val result = dev2SnapshotManager.restoreSnapshot(repositoryName, snapshotName1, "*")

          result.responseCode should be (403)
        }
      }
    }
  }

  override protected def beforeEach(): Unit = {
    adminSnapshotManager.deleteAllSnapshots()
    adminSnapshotManager.deleteAllRepositories().force()
    adminIndexManager.removeIndex("restored_index1")
    adminIndexManager.removeIndex("restored_index2")
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
  private object SnapshotNameGenerator {
    private val uniquePart = Atomic(0)
    def next(prefix: String): String = s"$prefix-${uniquePart.incrementAndGet()}"
  }
}