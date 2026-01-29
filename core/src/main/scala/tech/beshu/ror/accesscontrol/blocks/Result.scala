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
  final case class Fulfilled[B](context: B)
    extends Result[B]

  final case class Rejected[B](cause: Cause)
    extends Result[B]
  object Rejected {

    sealed trait Cause
    object Cause {
      sealed trait AuthenticationFailure extends Cause
      case object AuthenticationFailed extends AuthenticationFailure
      case object AuthenticationNotPossible extends AuthenticationFailure

      sealed trait AuthorizationFailure extends Cause
      case object GroupsAuthorizationFailed extends AuthorizationFailure
      case object GroupsAuthorizationNotPossible extends AuthorizationFailure
      case object NotAuthorized extends AuthorizationFailure

      sealed trait OtherFailure extends Cause
      case object ImpersonationNotSupported extends OtherFailure
      case object ImpersonationNotAllowed extends OtherFailure
      final case class IndexNotFound(allowedClusters: Set[ClusterName.Full]) extends OtherFailure
      case object AliasNotFound extends OtherFailure
      case object TemplateNotFound extends OtherFailure
    }
  }

  private[blocks] def resultBasedOnCondition[B <: BlockContext](blockContext: B)(condition: => Boolean): Result[B] = {
    if (condition) Fulfilled[B](blockContext)
    else Rejected[B](Cause.NotAuthorized)
  }

  def fulfilled[B](blockContext: B): Result[B] = Result.Fulfilled(blockContext)

  def rejected[B](cause: Cause): Result[B] = Result.Rejected(cause)

  def fromOption[A](opt: Option[A], ifEmptyCause: => Cause): Result[A] =
    opt match {
      case Some(value) => Fulfilled(value)
      case None => Rejected(ifEmptyCause)
    }

  extension [A](result: Result[A]) {
    def map[B](f: A => B): Result[B] = {
      result match {
        case Result.Fulfilled(a) => Fulfilled(f(a))
        case Result.Rejected(cause) => Result.Rejected(cause)
      }
    }

    def toEither: Either[Rejected[A], Fulfilled[A]] = result match {
      case fulfilled: Fulfilled[A] => Right(fulfilled)
      case rejected: Result.Rejected[A] => Left(Rejected(rejected.cause))
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
