package tech.beshu.ror.acl.blocks

import cats.Order
import tech.beshu.ror.acl.blocks.Variable.{ResolvedValue, ValueWithVariable}

sealed trait Value[T] {
  def getValue(resolver: VariablesResolver, blockContext: BlockContext): Option[T]
}

object Value {
  def fromString[T](representation: String, convert: ResolvedValue => T): Value[T] = {
    if(Variable.isStringVariable(representation)) Variable(ValueWithVariable(representation), convert) // todo: what about escaping
    else Const(convert(ResolvedValue(representation)))
  }

  implicit def valueOrder[T: Order]: Order[Value[T]] = Order.from {
    case (a: Const[T], b: Const[T]) => implicitly[Order[T]].compare(a.value, b.value)
    case (_: Const[T], _: Variable[T]) => -1
    case (_: Variable[T], _: Const[T]) => 1
    case (a: Variable[T], b: Variable[T]) => a.representation.raw.compareTo(b.representation.raw)
  }
}

final case class Variable[T](representation: ValueWithVariable,
                             convert: ResolvedValue => T)
  extends Value[T] {
  override def getValue(resolver: VariablesResolver, blockContext: BlockContext): Option[T] = {
    resolver
      .resolve(representation, blockContext)
      .map(convert)
  }
}

object Variable {

  def isStringVariable(value: String): Boolean = value.contains(VariablesManager.varDetector)

  final case class ValueWithVariable(raw: String) extends AnyVal
  final case class ResolvedValue(value: String) extends AnyVal
}

final case class Const[T](value: T)
  extends Value[T] {
  override def getValue(resolver: VariablesResolver, blockContext: BlockContext): Option[T] = Some(value)
}

trait VariablesResolver {
  def resolve(value: ValueWithVariable, blockContext: BlockContext): Option[ResolvedValue]
}