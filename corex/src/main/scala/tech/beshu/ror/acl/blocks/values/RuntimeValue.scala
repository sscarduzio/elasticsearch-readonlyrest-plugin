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
package tech.beshu.ror.acl.blocks.values

import cats.implicits._
import com.jayway.jsonpath.JsonPath
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.values.RuntimeValue.ExtractError
import tech.beshu.ror.acl.blocks.values.Variable.Unresolvable.{CannotExtractValue, CannotInstantiateResolvedValue}
import tech.beshu.ror.acl.blocks.values.Variable.{ConvertError, Unresolvable}
import tech.beshu.ror.acl.domain.Header
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.request.RequestContext

sealed trait RuntimeValue {
  def extract(requestContext: RequestContext,
              blockContext: BlockContext): Either[ExtractError, String]
}

object RuntimeValue {

  final case class ExtractError(msg: String) extends AnyVal
//

  //  // todo: what about escaping
  //  def fromString[T](representation: String, convert: ResolvedValue => Either[ConvertError, T]): Either[ConvertError, RuntimeValue[T]] = {
  //    if (Variable.checkIfStringContainsVariables(representation)) {
  //      Right(Variable(ValueWithVariable(representation), convert))
  //    } else {
  //      convert(ResolvedValue(representation)).map(Const.apply)
  //    }
  //  }

  //  implicit def valueOrder[T: Order]: Order[RuntimeValue[T]] = Order.from {
  //    case (a: Const[T], b: Const[T]) => implicitly[Order[T]].compare(a.value, b.value)
  //    case (_: Const[T], _: Variable[T]) => -1
  //    case (_: Variable[T], _: Const[T]) => 1
  //    case (a: Variable[T], b: Variable[T]) => a.representation.raw.compareTo(b.representation.raw)
  //  }
}

final case class Const(value: String) extends RuntimeValue {
  override def extract(requestContext: RequestContext,
                       blockContext: BlockContext): Either[ExtractError, String] = Right(value)
}

case object UserIdVar extends RuntimeValue {
  override def extract(requestContext: RequestContext,
                       blockContext: BlockContext): Either[ExtractError, String] = {
    blockContext
      .loggedUser
      .map(_.id.value) match {
      case Some(value) => Right(value)
      case None => Left(ExtractError("Cannot extract user ID from block context"))
    }
  }
}

final case class HeaderVar(header: Header.Name) extends RuntimeValue {
  override def extract(requestContext: RequestContext,
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

final case class JwtPayloadVar(jsonPath: JsonPath) extends RuntimeValue {
  override def extract(requestContext: RequestContext,
                       blockContext: BlockContext): Either[ExtractError, String] = ???
}

final case class Variable[T](values: List[RuntimeValue],
                             convert: String => Either[ConvertError, T]) {

  def resolve(requestContext: RequestContext,
              blockContext: BlockContext): Either[Unresolvable, T] = {
    values
      .foldLeft(Either.right[Unresolvable, String]("")) {
        case (Right(accumulator), value) =>
          value
            .extract(requestContext, blockContext)
            .left.map(error => CannotExtractValue(error.msg))
            .map(extracted => s"$accumulator$extracted")
        case (left@Left(_), _) =>
          left
      }
      .flatMap { resolved =>
        convert(resolved).left.map(error => CannotInstantiateResolvedValue(error.msg))
      }
  }
}

object Variable {

  final case class ConvertError(msg: String) extends AnyVal

  sealed trait Unresolvable
  object Unresolvable {
    final case class CannotExtractValue(msg: String) extends Unresolvable
    final case class CannotInstantiateResolvedValue(msg: String) extends Unresolvable
  }
}

//final case class Variable[T](representation: ValueWithVariable,
//                             convert: ResolvedValue => Either[RuntimeValue.ConvertError, T])
//  extends RuntimeValue[T] with Logging {
//  override def resolve(resolver: VariablesResolver, blockContext: BlockContext): Either[Unresolvable, T] = {
//    resolver
//      .resolve(representation, blockContext)
//      .map(convert)
//      .map {
//        _.left.map { error =>
//          logger.debug(s"Cannot instantiate '${error.resolvedValue.show}'. Reason: ${error.msg}")
//          CannotInstantiateResolvedValue
//        }
//      }
//      .getOrElse(Left(CannotResolveValue))
//  }
//}

//object Variable {
//
//  def checkIfStringContainsVariables(value: String): Boolean = value.contains(VariablesManager.varDetector)
//
//  final case class ValueWithVariable(raw: String) extends AnyVal
//  final case class ResolvedValue(value: String) extends AnyVal
//  object ResolvedValue {
//    implicit val show: Show[ResolvedValue] = Show.show(_.value)
//  }
//}

//trait VariablesResolver {
////  def resolve(value: ValueWithVariable, blockContext: BlockContext): Option[ResolvedValue]
//}