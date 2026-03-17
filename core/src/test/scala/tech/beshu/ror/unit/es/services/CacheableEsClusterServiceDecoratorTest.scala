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
package tech.beshu.ror.unit.es.services

import cats.data.NonEmptyList
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.es.services.EsClusterService.*
import tech.beshu.ror.es.services.{CacheableEsClusterServiceDecorator, EsClusterService}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.RefinedUtils.nes
import tech.beshu.ror.utils.WithDummyRequestIdSupport

class CacheableEsClusterServiceDecoratorTest
  extends AnyWordSpec
    with MockFactory
    with WithDummyRequestIdSupport {

  "CacheableEsClusterServiceDecorator" when {

    "allRemoteClusterNames is called" should {
      "call underlying only once for repeated calls" in {
        val decorator = decoratorWith { svc =>
          (svc.allRemoteClusterNames(_: RequestId)).expects(*).returning(Set.empty).once()
        }
        decorator.allRemoteClusterNames
        decorator.allRemoteClusterNames
        decorator.allRemoteClusterNames
      }
    }

    "allIndicesAndAliases is called" should {
      "call underlying only once for repeated calls" in {
        val decorator = decoratorWith { svc =>
          (svc.allIndicesAndAliases(_: RequestId)).expects(*).returning(Set.empty).once()
        }
        decorator.allIndicesAndAliases
        decorator.allIndicesAndAliases
        decorator.allIndicesAndAliases
      }
    }

    "allDataStreamsAndAliases is called" should {
      "call underlying only once for repeated calls" in {
        val decorator = decoratorWith { svc =>
          (svc.allDataStreamsAndAliases(_: RequestId)).expects(*).returning(Set.empty).once()
        }
        decorator.allDataStreamsAndAliases
        decorator.allDataStreamsAndAliases
        decorator.allDataStreamsAndAliases
      }
    }

    "legacyTemplates is called" should {
      "call underlying only once for repeated calls" in {
        val decorator = decoratorWith { svc =>
          (svc.legacyTemplates(_: RequestId)).expects(*).returning(Set.empty).once()
        }
        decorator.legacyTemplates
        decorator.legacyTemplates
        decorator.legacyTemplates
      }
    }

    "indexTemplates is called" should {
      "call underlying only once for repeated calls" in {
        val decorator = decoratorWith { svc =>
          (svc.indexTemplates(_: RequestId)).expects(*).returning(Set.empty).once()
        }
        decorator.indexTemplates
        decorator.indexTemplates
        decorator.indexTemplates
      }
    }

    "componentTemplates is called" should {
      "call underlying only once for repeated calls" in {
        val decorator = decoratorWith { svc =>
          (svc.componentTemplates(_: RequestId)).expects(*).returning(Set.empty).once()
        }
        decorator.componentTemplates
        decorator.componentTemplates
        decorator.componentTemplates
      }
    }

    "allSnapshots is called" should {
      "call underlying only once for repeated calls" in {
        val decorator = decoratorWith { svc =>
          (svc.allSnapshots(_: RequestId)).expects(*).returning(Map.empty).once()
        }
        decorator.allSnapshots
        decorator.allSnapshots
        decorator.allSnapshots
      }
    }

    "indexOrAliasUuids is called" should {
      "call underlying only once for the same index" in {
        val index = ClusterIndexName.Local(IndexName.Full(nes("my-index")))
        val decorator = decoratorWith { svc =>
          (svc.indexOrAliasUuids(_: IndexOrAlias)(_: RequestId)).expects(index, *).returning(Set("uuid-1")).once()
        }
        decorator.indexOrAliasUuids(index)
        decorator.indexOrAliasUuids(index)
        decorator.indexOrAliasUuids(index)
      }
      "call underlying separately for each distinct index" in {
        val index1 = ClusterIndexName.Local(IndexName.Full(nes("index-1")))
        val index2 = ClusterIndexName.Local(IndexName.Full(nes("index-2")))
        val decorator = decoratorWith { svc =>
          (svc.indexOrAliasUuids(_: IndexOrAlias)(_: RequestId)).expects(index1, *).returning(Set.empty).once()
          (svc.indexOrAliasUuids(_: IndexOrAlias)(_: RequestId)).expects(index2, *).returning(Set.empty).once()
        }
        decorator.indexOrAliasUuids(index1)
        decorator.indexOrAliasUuids(index2)
        decorator.indexOrAliasUuids(index1)
        decorator.indexOrAliasUuids(index2)
      }
    }

    "allRemoteIndicesAndAliases is called" should {
      "call underlying only once for repeated calls" in {
        val decorator = decoratorWith { svc =>
          (svc.allRemoteIndicesAndAliases(_: RequestId)).expects(*).returning(Task.now(Set.empty)).once()
        }
        val result = for {
          _ <- decorator.allRemoteIndicesAndAliases
          _ <- decorator.allRemoteIndicesAndAliases
          _ <- decorator.allRemoteIndicesAndAliases
        } yield ()
        result.runSyncUnsafe()
      }
    }

    "allRemoteDataStreamsAndAliases is called" should {
      "call underlying only once for repeated calls" in {
        val decorator = decoratorWith { svc =>
          (svc.allRemoteDataStreamsAndAliases(_: RequestId)).expects(*).returning(Task.now(Set.empty)).once()
        }
        val result = for {
          _ <- decorator.allRemoteDataStreamsAndAliases
          _ <- decorator.allRemoteDataStreamsAndAliases
          _ <- decorator.allRemoteDataStreamsAndAliases
        } yield ()
        result.runSyncUnsafe()
      }
    }

    "snapshotIndices is called" should {
      val repo = RepositoryName.Full.fromNes(nes("my-repo"))
      val snap = SnapshotName.Full.fromNes(nes("my-snap"))
      val repo2 = RepositoryName.Full.fromNes(nes("other-repo"))
      val snap2 = SnapshotName.Full.fromNes(nes("other-snap"))

      "call underlying only once for the same repo/snapshot pair" in {
        val decorator = decoratorWith { svc =>
          (svc.snapshotIndices(_: RepositoryName.Full, _: SnapshotName.Full)(_: RequestId))
            .expects(repo, snap, *).returning(Task.now(Set.empty)).once()
        }
        val result = for {
          _ <- decorator.snapshotIndices(repo, snap)
          _ <- decorator.snapshotIndices(repo, snap)
          _ <- decorator.snapshotIndices(repo, snap)
        } yield ()
        result.runSyncUnsafe()
      }
      "call underlying separately for each distinct repo/snapshot pair" in {
        val decorator = decoratorWith { svc =>
          (svc.snapshotIndices(_: RepositoryName.Full, _: SnapshotName.Full)(_: RequestId))
            .expects(repo, snap, *).returning(Task.now(Set.empty)).once()
          (svc.snapshotIndices(_: RepositoryName.Full, _: SnapshotName.Full)(_: RequestId))
            .expects(repo2, snap2, *).returning(Task.now(Set.empty)).once()
        }
        val result = for {
          _ <- decorator.snapshotIndices(repo, snap)
          _ <- decorator.snapshotIndices(repo2, snap2)
          _ <- decorator.snapshotIndices(repo, snap)
          _ <- decorator.snapshotIndices(repo2, snap2)
        } yield ()
        result.runSyncUnsafe()
      }
    }

    "verifyDocumentAccessibility is called" should {
      val index = ClusterIndexName.unsafeFromString("my-index")
      val doc1 = DocumentWithIndex(index, DocumentId("doc-1"))
      val doc2 = DocumentWithIndex(index, DocumentId("doc-2"))
      val filter = Filter(nes("my-filter"))

      "call underlying only once for the same document and filter" in {
        val decorator = decoratorWith { svc =>
          (svc.verifyDocumentAccessibility(_: Document, _: Filter)(_: RequestId))
            .expects(doc1, filter, *).returning(Task.now(DocumentAccessibility.Accessible)).once()
        }
        val result = for {
          _ <- decorator.verifyDocumentAccessibility(doc1, filter)
          _ <- decorator.verifyDocumentAccessibility(doc1, filter)
          _ <- decorator.verifyDocumentAccessibility(doc1, filter)
        } yield ()
        result.runSyncUnsafe()
      }
      "call underlying separately for each distinct document" in {
        val decorator = decoratorWith { svc =>
          (svc.verifyDocumentAccessibility(_: Document, _: Filter)(_: RequestId))
            .expects(doc1, filter, *).returning(Task.now(DocumentAccessibility.Accessible)).once()
          (svc.verifyDocumentAccessibility(_: Document, _: Filter)(_: RequestId))
            .expects(doc2, filter, *).returning(Task.now(DocumentAccessibility.Accessible)).once()
        }
        val result = for {
          _ <- decorator.verifyDocumentAccessibility(doc1, filter)
          _ <- decorator.verifyDocumentAccessibility(doc2, filter)
          _ <- decorator.verifyDocumentAccessibility(doc1, filter)
          _ <- decorator.verifyDocumentAccessibility(doc2, filter)
        } yield ()
        result.runSyncUnsafe()
      }
    }

    "verifyDocumentsAccessibility is called" should {
      val index = ClusterIndexName.unsafeFromString("my-index")
      val docs1 = NonEmptyList.of(DocumentWithIndex(index, DocumentId("doc-1")))
      val docs2 = NonEmptyList.of(DocumentWithIndex(index, DocumentId("doc-2")))
      val filter = Filter(nes("my-filter"))

      "call underlying only once for the same documents and filter" in {
        val decorator = decoratorWith { svc =>
          (svc.verifyDocumentsAccessibility(_: NonEmptyList[Document], _: Filter)(_: RequestId))
            .expects(docs1, filter, *).returning(Task.now(Map.empty)).once()
        }
        val result = for {
          _ <- decorator.verifyDocumentsAccessibility(docs1, filter)
          _ <- decorator.verifyDocumentsAccessibility(docs1, filter)
          _ <- decorator.verifyDocumentsAccessibility(docs1, filter)
        } yield ()
        result.runSyncUnsafe()
      }
      "call underlying separately for each distinct document list" in {
        val decorator = decoratorWith { svc =>
          (svc.verifyDocumentsAccessibility(_: NonEmptyList[Document], _: Filter)(_: RequestId))
            .expects(docs1, filter, *).returning(Task.now(Map.empty)).once()
          (svc.verifyDocumentsAccessibility(_: NonEmptyList[Document], _: Filter)(_: RequestId))
            .expects(docs2, filter, *).returning(Task.now(Map.empty)).once()
        }
        val result = for {
          _ <- decorator.verifyDocumentsAccessibility(docs1, filter)
          _ <- decorator.verifyDocumentsAccessibility(docs2, filter)
          _ <- decorator.verifyDocumentsAccessibility(docs1, filter)
          _ <- decorator.verifyDocumentsAccessibility(docs2, filter)
        } yield ()
        result.runSyncUnsafe()
      }
    }
  }

  private def decoratorWith(setup: EsClusterService => Unit): CacheableEsClusterServiceDecorator = {
    val underlying = mock[EsClusterService]
    setup(underlying)
    new CacheableEsClusterServiceDecorator(underlying)
  }
}
