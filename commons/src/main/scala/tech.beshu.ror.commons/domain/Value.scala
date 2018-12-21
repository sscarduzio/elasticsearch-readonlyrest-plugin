package tech.beshu.ror.commons.domain

sealed trait Value[T] {
  def getValue(resolver: VariablesResolver): Option[T]
}

object Value {
  def fromString[T](representation: String, covert: String => T): Value[T] = {
    if(representation.contains("@")) Variable(representation, covert) // todo: what about escaping
    else Const(covert(representation))
  }
}

final case class Variable[T](representation: String,
                             convert: String => T)
  extends Value[T] {
  override def getValue(resolver: VariablesResolver): Option[T] = {
    resolver
      .resolve(representation)
      .map(convert)
  }
}

final case class Const[T](value: T)
  extends Value[T] {
  override def getValue(resolver: VariablesResolver): Option[T] = Some(value)
}

trait VariablesResolver {
  // todo: request context or block context?
  def resolve(value: String): Option[String]
}