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
package tech.beshu.ror.es.request

import cats.{Monad, StackSafeMonad}
import tech.beshu.ror.accesscontrol.blocks.rules.utils.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances.identityNT
import tech.beshu.ror.accesscontrol.domain.InvolvingIndexOperation

trait RequestInfoShim {
  def extractType: String

  def getExpandedIndices(ixsSet: Set[String]): Set[String] = {
    if(!involvesIndices) {
      throw new IllegalArgumentException("can'g expand indices of a request that does not involve indices: " + extractAction)
    }
    val all = extractAllIndicesAndAliases
      .flatMap { case (indexName, aliases) =>
        aliases + indexName
      }
      .toSet
    MatcherWithWildcardsScalaAdapter.create(ixsSet).filter(all)
  }

  def extractIndexMetadata(index: String): Set[String]

  def extractTaskId: Long

  def extractContentLength: Int

  def extractContent: String

  def extractMethod: String

  def extractPath: String

  def extractIndices: ExtractedIndices

  def extractSnapshots: Set[String]

  def extractRepositories: Set[String]

  def extractAction: String

  def extractRequestHeaders: Map[String, String]

  def extractRemoteAddress: String

  def extractLocalAddress: String

  def extractId: String

  def extractAllIndicesAndAliases: Map[String, Set[String]]

  def extractTemplateIndicesPatterns: Set[String]

  def extractIsReadRequest: Boolean

  def extractIsAllowedForDLS: Boolean

  def extractIsCompositeRequest: Boolean

  def extractHasRemoteClusters: Boolean

  def indicesOperation: InvolvingIndexOperation = InvolvingIndexOperation.NonIndexOperation

  def involvesIndices: Boolean

  def writeIndices(newIndices: Set[String]): WriteResult[Unit]

  def writeRepositories(newRepositories: Set[String]): WriteResult[Unit]

  def writeSnapshots(newSnapshots: Set[String]): WriteResult[Unit]

  def writeResponseHeaders(hMap: Map[String, String]): WriteResult[Unit]

  def writeToThreadContextHeaders(hMap: Map[String, String]): WriteResult[Unit]

  def writeTemplatesOf(indices: Set[String]): WriteResult[Unit]
}

object RequestInfoShim {

  // todo: to remove
  sealed trait ExtractedIndices {
    def indices: Set[String]
  }
  object ExtractedIndices {
    case object NoIndices extends ExtractedIndices {
      override def indices: Set[String] = Set.empty
    }
    final case class RegularIndices(override val indices: Set[String]) extends ExtractedIndices
    sealed trait SqlIndices extends ExtractedIndices {
      def indices: Set[String]
    }
    object SqlIndices {
      final case class SqlTableRelated(tables: List[IndexSqlTable]) extends SqlIndices {
        override lazy val indices: Set[String] = tables.flatMap(_.indices).toSet
      }
      object SqlTableRelated {
        final case class IndexSqlTable(tableStringInQuery: String, indices: Set[String])
      }
      case object SqlNotTableRelated extends SqlIndices {
        override def indices: Set[String] = Set.empty
      }
    }
  }

  sealed trait WriteResult[+T]
  object WriteResult {
    final case class Success[T](value: T) extends WriteResult[T]
    case object Failure extends WriteResult[Nothing]

    implicit val monad: Monad[WriteResult] = new StackSafeMonad[WriteResult] {
      override def flatMap[A, B](fa: WriteResult[A])(f: A => WriteResult[B]): WriteResult[B] = fa match {
        case Success(value) => f(value)
        case Failure => Failure
      }

      override def pure[A](x: A): WriteResult[A] = Success(x)
    }
  }
}