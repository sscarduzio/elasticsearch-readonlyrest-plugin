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
package tech.beshu.ror.acl.blocks

import cats.implicits._
import cats.{Order, Show}
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.blocks.Value.Unresolvable
import tech.beshu.ror.acl.blocks.Value.Unresolvable.{CannotInstantiateResolvedValue, CannotResolveValue}
import tech.beshu.ror.acl.blocks.Variable.{ResolvedValue, ValueWithVariable}

sealed trait Value[T] {
  def get(resolver: VariablesResolver, blockContext: BlockContext): Either[Unresolvable, T]
}

object Value {

  sealed trait Unresolvable
  object Unresolvable {
    case object CannotResolveValue extends Unresolvable
    case object CannotInstantiateResolvedValue extends Unresolvable
  }

  final case class ConvertError(resolvedValue: ResolvedValue, msg: String)

  // todo: what about escaping
  def fromString[T](representation: String, convert: ResolvedValue => Either[ConvertError, T]): Either[ConvertError, Value[T]] = {
    if (Variable.checkIfStringContainsVariables(representation)) {
      Right(Variable(ValueWithVariable(representation), convert))
    } else {
      convert(ResolvedValue(representation)).map(Const.apply)
    }
  }

  implicit def valueOrder[T: Order]: Order[Value[T]] = Order.from {
    case (a: Const[T], b: Const[T]) => implicitly[Order[T]].compare(a.value, b.value)
    case (_: Const[T], _: Variable[T]) => -1
    case (_: Variable[T], _: Const[T]) => 1
    case (a: Variable[T], b: Variable[T]) => a.representation.raw.compareTo(b.representation.raw)
  }
}

final case class Variable[T](representation: ValueWithVariable,
                             convert: ResolvedValue => Either[Value.ConvertError, T])
  extends Value[T] with Logging {
  override def get(resolver: VariablesResolver, blockContext: BlockContext): Either[Unresolvable, T] = {
    resolver
      .resolve(representation, blockContext)
      .map(convert)
      .map {
        _.left.map { error =>
          logger.debug(s"Cannot instantiate '${error.resolvedValue.show}'. Reason: ${error.msg}")
          CannotInstantiateResolvedValue
        }
      }
      .getOrElse(Left(CannotResolveValue))
  }
}

object Variable {

  def checkIfStringContainsVariables(value: String): Boolean = value.contains(VariablesManager.varDetector)

  final case class ValueWithVariable(raw: String) extends AnyVal
  final case class ResolvedValue(value: String) extends AnyVal
  object ResolvedValue {
    implicit val show: Show[ResolvedValue] = Show.show(_.value)
  }
}

final case class Const[T](value: T)
  extends Value[T] {
  override def get(resolver: VariablesResolver, blockContext: BlockContext): Either[Unresolvable, T] = Right(value)
}

trait VariablesResolver {
  def resolve(value: ValueWithVariable, blockContext: BlockContext): Option[ResolvedValue]
}