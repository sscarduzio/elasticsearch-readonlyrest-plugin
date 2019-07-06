package tech.beshu.ror.acl.blocks.variables.runtime

import cats.data.NonEmptyList
import cats.implicits._
import cats.kernel.Monoid
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.variables.runtime.Extractable.ExtractError
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.Unresolvable
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.Unresolvable.{CannotExtractValue, CannotInstantiateResolvedValue}
import tech.beshu.ror.acl.domain.{ClaimName, Header}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.utils.ClaimsOps._
import tech.beshu.ror.acl.utils.ClaimsOps.ClaimSearchResult.{Found, NotFound}
import tech.beshu.ror.com.jayway.jsonpath.JsonPath

private [runtime] trait RuntimeResolvableVariable[RESULT] {

  def resolve(requestContext: RequestContext,
              blockContext: BlockContext): Either[Unresolvable, RESULT]
}

object RuntimeResolvableVariable {

  final case class ConvertError(msg: String)

  sealed trait Unresolvable
  object Unresolvable {
    final case class CannotExtractValue(msg: String) extends Unresolvable
    final case class CannotInstantiateResolvedValue(msg: String) extends Unresolvable
  }

  protected [runtime] abstract class AlreadyResolved[VALUE](value: VALUE)
    extends RuntimeResolvableVariable[VALUE] {

    override def resolve(requestContext: RequestContext,
                         blockContext: BlockContext): Either[Unresolvable, VALUE] =
      Right(value)
  }

  protected [runtime] abstract class ToBeResolved[RESULT, VALUE : Monoid](values: NonEmptyList[Extractable[VALUE]],
                                                                          convert: VALUE => Either[ConvertError, RESULT])
    extends RuntimeResolvableVariable[RESULT] {

    override def resolve(requestContext: RequestContext,
                         blockContext: BlockContext): Either[Unresolvable, RESULT] = {
      values
        .foldLeft(Either.right[Unresolvable, VALUE](implicitly[Monoid[VALUE]].empty)) {
          case (Right(accumulator), value) =>
            value
              .extractUsing(requestContext, blockContext)
              .left.map(error => CannotExtractValue(error.msg))
              .map(extracted => implicitly[Monoid[VALUE]].combine(accumulator, extracted))
          case (left@Left(_), _) =>
            left
        }
        .flatMap { resolved =>
          convert(resolved).left.map(error => CannotInstantiateResolvedValue(error.msg))
        }
    }
  }
}

sealed trait Extractable[VALUE] {

  def extractUsing(requestContext: RequestContext,
                   blockContext: BlockContext): Either[ExtractError, VALUE]
}
object Extractable {

  final case class ExtractError(msg: String) extends AnyVal

}

sealed trait SingleExtractable extends Extractable[String]
object SingleExtractable {

  final case class Const(value: String) extends SingleExtractable {
    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, String] = Right(value)
  }

  case object UserIdVar extends SingleExtractable {
    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, String] = {
      blockContext
        .loggedUser
        .map(_.id.value) match {
        case Some(value) => Right(value)
        case None => Left(ExtractError("Cannot extract user ID from block context"))
      }
    }
  }

  final case class HeaderVar(header: Header.Name) extends SingleExtractable {
    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, String] = {
      requestContext
        .headers
        .find(_.name === header)
        .map(_.value.value) match {
        case Some(value) => Right(value)
        case None => Left(ExtractError(s"Cannot extract user header '${header.show}' from request context"))
      }
    }
  }

  final case class JwtPayloadVar(jsonPath: JsonPath) extends SingleExtractable {
    private val varClaim = ClaimName(jsonPath)

    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, String] = {
      blockContext.jsonToken match {
        case Some(payload) =>
          payload.claims.customClaim(varClaim) match {
            case Found(values) =>
              Right(values.toList.mkString(","))
            case NotFound =>
              Left(ExtractError(s"Cannot find value string or collection of strings in path '${jsonPath.show}' of JWT Token"))
          }
        case None =>
          Left(ExtractError("Cannot extract JSON token payload from block context"))
      }
    }
  }
}

sealed trait MultiExtractable extends Extractable[List[String]]
object MultiExtractable {

  def fromSingleExtractable(extractable: SingleExtractable): MultiExtractable = {
    extractable match {
      case SingleExtractable.Const(value) => MultiExtractable.Const(value)
      case SingleExtractable.UserIdVar => MultiExtractable.UserIdVar
      case SingleExtractable.HeaderVar(header) => MultiExtractable.HeaderVar(header)
      case SingleExtractable.JwtPayloadVar(jsonPath) => MultiExtractable.JwtPayloadVar(jsonPath)
    }
  }

  final case class Const(value: String) extends MultiExtractable {
    private val singleConst = SingleExtractable.Const(value)
    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, List[String]] =
      singleConst
        .extractUsing(requestContext, blockContext)
        .map(List(_))
  }

  case object UserIdVar extends MultiExtractable {
    private val singleUserIdExtractable = SingleExtractable.UserIdVar
    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, List[String]] = {
      singleUserIdExtractable
        .extractUsing(requestContext, blockContext)
        .map(List(_))
    }
  }

  final case class HeaderVar(header: Header.Name) extends MultiExtractable {
    private val singleHeaderExtractable = SingleExtractable.HeaderVar(header)
    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, List[String]] = {
      singleHeaderExtractable
        .extractUsing(requestContext, blockContext)
        .map(List(_))
    }
  }

  final case class JwtPayloadVar(jsonPath: JsonPath) extends MultiExtractable {
    private val singleJwtPayloadExtractable = SingleExtractable.JwtPayloadVar(jsonPath)

    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, List[String]] = {
      singleJwtPayloadExtractable
        .extractUsing(requestContext, blockContext)
        .map(List(_))
    }
  }

}
