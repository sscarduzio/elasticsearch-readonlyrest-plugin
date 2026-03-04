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
import cats.data.EitherT
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.syntax.*

import scala.annotation.tailrec

sealed trait Decision[CONTEXT]

object Decision {
  final case class Permitted[CONTEXT](context: CONTEXT)
    extends Decision[CONTEXT]

  final case class Denied[CONTEXT](cause: Cause)
    extends Decision[CONTEXT]
  object Denied {

    sealed trait Cause
    object Cause {
      sealed trait AuthenticationFailure extends Cause
      final case class AuthenticationFailed(details: String) extends AuthenticationFailure

      sealed trait AuthorizationFailure extends Cause
      final case class GroupsAuthorizationFailed(details: String) extends AuthorizationFailure
      case object NotAuthorized extends AuthorizationFailure

      sealed trait OtherFailure extends Cause
      case object ImpersonationNotSupported extends OtherFailure
      case object ImpersonationNotAllowed extends OtherFailure
      final case class IndexNotFound(allowedClusters: Set[ClusterName.Full]) extends OtherFailure
      case object AliasNotFound extends OtherFailure
      case object TemplateNotFound extends OtherFailure
    }
  }

  def permit[CONTEXT](`with`: CONTEXT)(when: => Boolean): Decision[CONTEXT] = {
    if (when) Permitted[CONTEXT](`with`)
    else Denied[CONTEXT](Cause.NotAuthorized)
  }

  def permit[CONTEXT](blockContext: CONTEXT): Decision[CONTEXT] = Decision.Permitted(blockContext)

  def deny[CONTEXT](cause: Cause): Decision[CONTEXT] = Decision.Denied(cause)

  def fromOption[CONTEXT](opt: Option[CONTEXT], ifEmptyCause: => Cause): Decision[CONTEXT] =
    opt match {
      case Some(value) => Permitted(value)
      case None => Denied(ifEmptyCause)
    }

  extension [A](result: Decision[A]) {
    def map[B](f: A => B): Decision[B] = {
      result match {
        case Decision.Permitted(a) => Permitted(f(a))
        case Decision.Denied(cause) => Decision.Denied(cause)
      }
    }

    def toEither: Either[Denied[A], Permitted[A]] = result match {
      case fulfilled: Permitted[A] => Right(fulfilled)
      case rejected: Decision.Denied[A] => Left(Denied(rejected.cause))
    }
  }

  extension [C <: Cause, R](result: Either[C, R]) {
    def toDecision: Decision[R] = {
      result match {
        case Right(r) => Decision.permit[R](r)
        case Left(cause) => Decision.deny[R](cause)
      }
    }
  }

  extension[C <: Cause, R] (result: EitherT[Task, C, R]) {
    def toDecision: Task[Decision[R]] =
      result.value.map(_.toDecision)
  }

  implicit val resultMonad: Monad[Decision] = new Monad[Decision] {
    override def pure[A](a: A): Decision[A] =
      Decision.Permitted(a)

    override def flatMap[A, B](fa: Decision[A])(f: A => Decision[B]): Decision[B] =
      fa match {
        case Decision.Permitted(value) => f(value)
        case Decision.Denied(cause) => Decision.Denied(cause)
      }

    @tailrec
    override def tailRecM[A, B](a: A)(f: A => Decision[Either[A, B]]): Decision[B] =
      f(a) match {
        case Decision.Permitted(Left(next)) => tailRecM(next)(f)
        case Decision.Permitted(Right(b)) => Decision.Permitted(b)
        case Decision.Denied(cause) => Decision.Denied(cause)
      }
  }
}
