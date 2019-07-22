package tech.beshu.ror.acl.blocks.rules.impersonation

import cats.data.EitherT
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.acl.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.impersonation.ImpersonationSupport.UserExistence.{CannotCheck, Exists, NotExist}
import tech.beshu.ror.acl.blocks.rules.utils.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.acl.blocks.rules.utils.StringTNaturalTransformation.instances.stringUserIdNT
import tech.beshu.ror.acl.blocks.{BlockContext, NoOpBlockContext}
import tech.beshu.ror.acl.domain.{LoggedUser, User}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._
import tech.beshu.ror.utils.MatcherWithWildcards

import scala.collection.JavaConverters._

class ImpersonationRuleDecorator[R <: AuthenticationRule with ImpersonationSupport](val underlying: R,
                                                                                    impersonators: List[ImpersonatorDef])
  extends AuthenticationRule {

  private val enhancedImpersonatorDefs =
    impersonators
      .map { i =>
        val matcher = new MatcherWithWildcardsScalaAdapter(
          new MatcherWithWildcards(i.users.map(_.value.value).toSortedSet.asJava)
        )
        (i, matcher)
      }
  override val name: Rule.Name = underlying.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[Rule.RuleResult] = {
    requestContext.impersonateAs match {
      case Some(theImpersonatedUserId) => toRuleResult {
        for {
          impersonatorDef <- findImpersonator(theImpersonatedUserId, requestContext)
          _ <- authenticateImpersonator(impersonatorDef, requestContext)
          _ <- checkIfTheImpersonatedUserExist(theImpersonatedUserId)
        } yield blockContext.withLoggedUser(LoggedUser(theImpersonatedUserId))
      }
      case None =>
        underlying.check(requestContext, blockContext)
    }
  }

  private def findImpersonator(theImpersonatedUserId: User.Id,
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
      ifNone = ()
    )
  }

  private def authenticateImpersonator(impersonatorDef: ImpersonatorDef,
                                       requestContext: RequestContext) = EitherT {
    impersonatorDef
      .authenticationRule
      .check(requestContext, NoOpBlockContext)
      .map {
        case Fulfilled(_) => Right(())
        case Rejected => Left(())
      }
  }

  private def checkIfTheImpersonatedUserExist(theImpersonatedUserId: User.Id) = EitherT {
    underlying
      .exists(theImpersonatedUserId)
      .map {
        case Exists => Right(())
        case NotExist => Left(())
        case CannotCheck => Left(()) // todo: maybe we can indicate it somehow
      }
  }

  private def toRuleResult(result: EitherT[Task, Unit, BlockContext]) = {
    result
      .value
      .map {
        case Right(newBlockContext) => Fulfilled(newBlockContext)
        case Left(_) => Rejected
      }
  }
}
