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

import cats.Monad
import tech.beshu.ror.accesscontrol.blocks.Result.Rejected.Cause
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.syntax.*

import scala.annotation.tailrec

sealed trait Result[B]

object Result {
  final case class Fulfilled[B](blockContext: B)
    extends Result[B]

  final case class Rejected[B](specialCause: Option[Cause] = None)
    extends Result[B]
  object Rejected {
    def apply[B <: BlockContext](specialCause: Cause): Rejected[B] = new Rejected(Some(specialCause))

    sealed trait Cause
    object Cause {
      final case class AuthenticationFailed(details: String) extends Cause
      final case class AuthenticationNotPossible(details: String) extends Cause
      final case class GroupsAuthorizationFailed(details: String) extends Cause
      final case class GroupsAuthorizationNotPossible(details: String) extends Cause
      case object ImpersonationNotSupported extends Cause
      case object ImpersonationNotAllowed extends Cause
      final case class IndexNotFound(allowedClusters: Set[ClusterName.Full]) extends Cause
      case object AliasNotFound extends Cause
      case object TemplateNotFound extends Cause
    }
  }

  private [blocks] def resultBasedOnCondition[B <: BlockContext](blockContext: B)(condition: => Boolean): Result[B] = {
    if (condition) Fulfilled[B](blockContext)
    else Rejected[B]()
  }

  private [blocks] def fulfilled[B <: BlockContext](blockContext: B): Result[B] = Result.Fulfilled(blockContext)

  private [blocks] def rejected[B <: BlockContext](specialCause: Option[Cause] = None): Result[B] = Result.Rejected(specialCause)

  def fromOption[A](opt: Option[A], ifEmpty: => Rejected[A] = Rejected[A]()): Result[A] =
    opt match {
      case Some(value) => Fulfilled(value)
      case None => ifEmpty
    }

  extension [B](result: Result[B]) {
    def withFilter(p: B => Boolean): Result[B] =
      result match {
        case Result.Fulfilled(a) if p(a) => result
        case _ => Result.Rejected(None)
      }
  }

  implicit val resultMonad: Monad[Result] = new Monad[Result] {
    override def pure[A](a: A): Result[A] =
      Result.Fulfilled(a)

    override def flatMap[A, B](fa: Result[A])(f: A => Result[B]): Result[B] =
      fa match {
        case Result.Fulfilled(value) => f(value)
        case Result.Rejected(cause) => Result.Rejected(cause)
      }

    @tailrec
    override def tailRecM[A, B](a: A)(f: A => Result[Either[A, B]]): Result[B] =
      f(a) match {
        case Result.Fulfilled(Left(next)) => tailRecM(next)(f)
        case Result.Fulfilled(Right(b)) => Result.Fulfilled(b)
        case Result.Rejected(cause) => Result.Rejected(cause)
      }
  }
}
