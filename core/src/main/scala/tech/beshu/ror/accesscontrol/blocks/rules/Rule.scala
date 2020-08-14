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
package tech.beshu.ror.accesscontrol.blocks.rules

import cats.Show
import cats.data.EitherT
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.UserExistence
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.UserExistence.{CannotCheck, Exists, NotExist}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{Name, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.utils.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances._
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableUsage
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.ImpersonatedUser
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.utils.MatcherWithWildcards

import scala.collection.JavaConverters._

sealed trait Rule {
  def name: Name

  def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]]
}

object Rule {

  final case class RuleWithVariableUsageDefinition[+T <: Rule](rule: T, variableUsage: VariableUsage[T])

  object RuleWithVariableUsageDefinition {
    def create[T <: Rule : VariableUsage](rule: T) = new RuleWithVariableUsageDefinition(rule, implicitly[VariableUsage[T]])
  }

  sealed trait RuleResult[B <: BlockContext]
  object RuleResult {
    final case class Fulfilled[B <: BlockContext](blockContext: B)
      extends RuleResult[B]
    final case class Rejected[B <: BlockContext](specialCause: Option[Cause] = None)
      extends RuleResult[B]
    object Rejected {
      def apply[B <: BlockContext](specialCause: Cause): Rejected[B] = new Rejected(Some(specialCause))
      sealed trait Cause
      object Cause {
        case object ImpersonationNotSupported extends Cause
        case object ImpersonationNotAllowed extends Cause
        case object IndexNotFound extends Cause
        case object AliasNotFound extends Cause
      }
    }

    private[rules] def fromCondition[B <: BlockContext](blockContext: B)(condition: => Boolean): RuleResult[B] = {
      if (condition) Fulfilled[B](blockContext)
      else Rejected[B]()
    }
  }

  final case class Name(value: String) extends AnyVal
  object Name {
    implicit val show: Show[Name] = Show.show(_.value)
  }

  trait MatchingAlwaysRule {
    this: Rule =>

    def process[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[B]

    override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
      process(blockContext).map(RuleResult.Fulfilled.apply)
  }

  trait RegularRule extends Rule

  trait AuthorizationRule extends Rule

  trait AuthenticationRule extends Rule {

    private lazy val enhancedImpersonatorDefs =
      impersonators
        .map { i =>
          val matcher = new MatcherWithWildcardsScalaAdapter(
            new MatcherWithWildcards(i.users.map(_.value.value).toSortedSet.asJava)
          )
          (i, matcher)
        }

    protected def impersonators: List[ImpersonatorDef]

    protected def exists(user: User.Id): Task[UserExistence]

    def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]]

    override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] = {
      val requestContext = blockContext.requestContext
      requestContext.impersonateAs match {
        case Some(theImpersonatedUserId) => toRuleResult[B] {
          for {
            impersonatorDef <- findImpersonatorWithProperRights[B](theImpersonatedUserId, requestContext)
            _ <- authenticateImpersonator(impersonatorDef, blockContext)
            _ <- checkIfTheImpersonatedUserExist[B](theImpersonatedUserId)
          } yield {
            blockContext.withUserMetadata(_.withLoggedUser(ImpersonatedUser(theImpersonatedUserId, impersonatorDef.id)))
          }
        }
        case None =>
          tryToAuthenticate(blockContext)
      }
    }

    private def findImpersonatorWithProperRights[B <: BlockContext](theImpersonatedUserId: User.Id,
                                                                    requestContext: RequestContext) = {
      EitherT.fromOption[Task](
        requestContext
          .basicAuth
          .flatMap { basicAuthCredentials =>
            enhancedImpersonatorDefs.find(_._1.id === basicAuthCredentials.credentials.user)
          }
          .flatMap { case (impersonatorDef, matcher) =>
            if (matcher.`match`(theImpersonatedUserId)) Some(impersonatorDef)
            else None
          },
        ifNone = Rejected[B](Cause.ImpersonationNotAllowed)
      )
    }

    private def authenticateImpersonator[B <: BlockContext : BlockContextUpdater](impersonatorDef: ImpersonatorDef,
                                                                                  blockContext: B) = EitherT {
      impersonatorDef
        .authenticationRule
        .tryToAuthenticate(BlockContextUpdater[B].emptyBlockContext(blockContext)) // we are not interested in gathering those data
        .map {
          case Fulfilled(_) => Right(())
          case Rejected(_) => Left(Rejected[B](Cause.ImpersonationNotAllowed))
        }
    }

    private def checkIfTheImpersonatedUserExist[B <: BlockContext](theImpersonatedUserId: User.Id) = EitherT {
      exists(theImpersonatedUserId)
        .map {
          case Exists => Right(())
          case NotExist => Left(Rejected[B]())
          case CannotCheck => Left(Rejected[B](Cause.ImpersonationNotSupported))
        }
    }

    private def toRuleResult[B <: BlockContext](result: EitherT[Task, Rejected[B], B]): Task[RuleResult[B]] = {
      result
        .value
        .map {
          case Right(newBlockContext) => Fulfilled[B](newBlockContext)
          case Left(rejected) => rejected
        }
    }
  }
  object AuthenticationRule {
    sealed trait UserExistence
    object UserExistence {
      case object Exists extends UserExistence
      case object NotExist extends UserExistence
      case object CannotCheck extends UserExistence
    }
  }

  trait NoImpersonationSupport {
    this: AuthenticationRule =>

    override protected val impersonators: List[ImpersonatorDef] = Nil

    override protected def exists(user: User.Id): Task[UserExistence] = Task.now(CannotCheck)
  }

}
