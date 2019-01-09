package tech.beshu.ror.acl.blocks

import cats.implicits._
import cats.{Order, Show}
import com.typesafe.scalalogging.StrictLogging
import tech.beshu.ror.acl.blocks.Value.Unresolvable
import tech.beshu.ror.acl.blocks.Value.Unresolvable.{CannotInstantiateResolvedValue, CannotResolveValue}
import tech.beshu.ror.acl.blocks.Variable.{ResolvedValue, ValueWithVariable}

sealed trait Value[T] {
  def getValue(resolver: VariablesResolver, blockContext: BlockContext): Either[Unresolvable, T]
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
    if (Variable.isStringVariable(representation)) {
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
  extends Value[T] with StrictLogging {
  override def getValue(resolver: VariablesResolver, blockContext: BlockContext): Either[Unresolvable, T] = {
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

  def isStringVariable(value: String): Boolean = value.contains(VariablesManager.varDetector)

  final case class ValueWithVariable(raw: String) extends AnyVal
  final case class ResolvedValue(value: String) extends AnyVal
  object ResolvedValue {
    implicit val show: Show[ResolvedValue] = Show.show(_.value)
  }
}

final case class Const[T](value: T)
  extends Value[T] {
  override def getValue(resolver: VariablesResolver, blockContext: BlockContext): Either[Unresolvable, T] = Right(value)
}

trait VariablesResolver {
  def resolve(value: ValueWithVariable, blockContext: BlockContext): Option[ResolvedValue]
}