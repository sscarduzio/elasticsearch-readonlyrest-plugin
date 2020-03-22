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
package tech.beshu.ror.accesscontrol.blocks

import cats.{Eval, Foldable, Functor}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.Outcome
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.Operation.{AnIndexOperation, _}
import tech.beshu.ror.accesscontrol.domain.{Operation, _}
import tech.beshu.ror.accesscontrol.request.RequestContext

sealed trait BlockContext[B <: BlockContext[B]] {

  type OPERATION <: Operation
  def requestContext: RequestContext.Aux[OPERATION, B]

  def userMetadata: UserMetadata
  def withUserMetadata(update: UserMetadata => UserMetadata): B

  def responseHeaders: Set[Header]
  def withAddedResponseHeader(header: Header): B

  def contextHeaders: Set[Header]
  def withAddedContextHeader(header: Header): B

  def indices: Outcome[Set[IndexName]]
  def withIndices(indices: Set[IndexName]): B
}

object BlockContext {
  type Aux[B <: BlockContext[B], O <: Operation] = BlockContext[B] { type OPERATION = O }

  sealed trait Outcome[+T] {
    def getOrElse[S >: T](default: => S): S = this match {
      case Outcome.Exist(value) => value
      case Outcome.NotExist => default
    }
  }
  object Outcome {
    final case class Exist[T](value: T) extends Outcome[T]
    case object NotExist extends Outcome[Nothing]

    implicit val functor: Functor[Outcome] = new Functor[Outcome] {
      override def map[A, B](fa: Outcome[A])(f: A => B): Outcome[B] = fa match {
        case Exist(value) => Exist(f(value))
        case NotExist => NotExist
      }
    }
    implicit val foldable: Foldable[Outcome] = new Foldable[Outcome] {
      override def foldLeft[A, B](fa: Outcome[A], b: B)(f: (B, A) => B): B = {
        fa match {
          case Exist(a) => f(b, a)
          case NotExist => b
        }
      }

      override def foldRight[A, B](fa: Outcome[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = {
        fa match {
          case Exist(a) => f(a, lb)
          case NotExist => lb
        }
      }
    }
  }

  sealed trait IndexRelated
  object IndexRelated {
    final case class Itself(index: IndexName) extends IndexRelated
    final case class Template(name: Template, indices: Set[IndexName]) extends IndexRelated
  }
}

trait NonIndexOperationBlockContext[B <: NonIndexOperationBlockContext[B]]
  extends BlockContext[B] {
  override type OPERATION <: NonIndexOperation
}

class CurrentUserMetadataOperationBlockContext(override val requestContext: RequestContext.Aux[Operation.CurrentUserMetadataOperation.type, CurrentUserMetadataOperationBlockContext])
  extends NonIndexOperationBlockContext[CurrentUserMetadataOperationBlockContext] {

  override type OPERATION = CurrentUserMetadataOperation.type

  override def responseHeaders: Set[Header] = ???

  override def withAddedResponseHeader(header: Header): CurrentUserMetadataOperationBlockContext = ???

  override def contextHeaders: Set[Header] = ???

  override def withAddedContextHeader(header: Header): CurrentUserMetadataOperationBlockContext = ???

  override def indices: Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): CurrentUserMetadataOperationBlockContext = ???

  override def repositories: Outcome[Set[IndexName]] = ???

  override def withRepositories(indices: Set[IndexName]): CurrentUserMetadataOperationBlockContext = ???

  override def snapshots: Outcome[Set[IndexName]] = ???

  override def withSnapshots(indices: Set[IndexName]): CurrentUserMetadataOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): CurrentUserMetadataOperationBlockContext = ???
}

trait RepositoryOperationBlockContext[B <: NonIndexOperationBlockContext[B]]
  extends BlockContext[B] {
  override type OPERATION <: RepositoryOperation

  def repositories: Outcome[Set[Repository]]
  def withRepositories(indices: Set[Repository]): B
}

sealed trait AnIndexOperationBlockContext[B <: AnIndexOperationBlockContext[B]]
  extends BlockContext[B] {
  override type OPERATION <: AnIndexOperation
}

sealed trait DirectIndexOperationBlockContext[B <: DirectIndexOperationBlockContext[B]]
  extends AnIndexOperationBlockContext[B] {
  override type OPERATION <: DirectIndexOperation

  def filteredIndices: Outcome[Set[IndexName]]
  def withFilteredIndices(indices: Set[IndexName]): B
}

sealed trait IndirectIndexOperationBlockContext[B <: IndirectIndexOperationBlockContext[B]]
  extends AnIndexOperationBlockContext[B] {
  override type OPERATION <: IndirectIndexOperation
}

trait GeneralIndexOperationBlockContext[B <: GeneralIndexOperationBlockContext[B]]
  extends DirectIndexOperationBlockContext[B] {
  override type OPERATION <: GeneralIndexOperation
}

sealed trait TemplateOperationBlockContext[B <: TemplateOperationBlockContext[B]]
  extends IndirectIndexOperationBlockContext[B] {
  override type OPERATION <: TemplateOperation
}

// todo: maybe we don't need it?
trait GetTemplateOperationBlockContext[B <: GetTemplateOperationBlockContext[B]]
  extends TemplateOperationBlockContext[B] {
  override type OPERATION <: TemplateOperation.Get
}

trait CreateTemplateOperationBlockContext[B <: CreateTemplateOperationBlockContext[B]]
  extends TemplateOperationBlockContext[B] {
  override type OPERATION <: TemplateOperation.Create
}

trait DeleteTemplateOperationBlockContext[B <: DeleteTemplateOperationBlockContext[B]]
  extends TemplateOperationBlockContext[B] {
  override type OPERATION <: TemplateOperation.Delete
}

trait SnapshotOperationBlockContext[B <: SnapshotOperationBlockContext[B]]
  extends IndirectIndexOperationBlockContext[B] {
  override type OPERATION <: SnapshotOperation

  def snapshots: Outcome[Set[Snapshot]]
  def withSnapshots(snapshots: Set[Snapshot]): B
}
