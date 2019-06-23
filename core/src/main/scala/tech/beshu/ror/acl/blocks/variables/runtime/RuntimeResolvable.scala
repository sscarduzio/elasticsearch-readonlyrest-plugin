package tech.beshu.ror.acl.blocks.variables.runtime

import cats.implicits._
import cats.kernel.Monoid
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.variables.runtime.Extractable.ExtractError
import tech.beshu.ror.acl.blocks.variables.runtime.Variable.Unresolvable
import tech.beshu.ror.acl.blocks.variables.runtime.Variable.Unresolvable.{CannotExtractValue, CannotInstantiateResolvedValue}
import tech.beshu.ror.acl.request.RequestContext

private [runtime] trait Variable[T] {

  def resolve(requestContext: RequestContext,
              blockContext: BlockContext): Either[Unresolvable, T]
}

object Variable {

  final case class ConvertError(msg: String)

  sealed trait Unresolvable
  object Unresolvable {
    final case class CannotExtractValue(msg: String) extends Unresolvable
    final case class CannotInstantiateResolvedValue(msg: String) extends Unresolvable
  }

  protected [runtime] abstract class AlreadyResolved[T](value: T)
    extends Variable[T] {

    override def resolve(requestContext: RequestContext,
                         blockContext: BlockContext): Either[Unresolvable, T] =
      Right(value)
  }

  protected [runtime] abstract class ToBeResolved[T, S : Monoid](values: List[Extractable[S]],
                                                                 convert: S => Either[ConvertError, T])
    extends Variable[T] {

    override def resolve(requestContext: RequestContext,
                         blockContext: BlockContext): Either[Unresolvable, T] = {
      values
        .foldLeft(Either.right[Unresolvable, S](implicitly[Monoid[S]].empty)) {
          case (Right(accumulator), value) =>
            value
              .extractUsing(requestContext, blockContext)
              .left.map(error => CannotExtractValue(error.msg))
              .map(extracted => implicitly[Monoid[S]].combine(accumulator, extracted))
          case (left@Left(_), _) =>
            left
        }
        .flatMap { resolved =>
          convert(resolved).left.map(error => CannotInstantiateResolvedValue(error.msg))
        }
    }
  }
}

trait Extractable[T] {

  def extractUsing(requestContext: RequestContext,
                   blockContext: BlockContext): Either[ExtractError, T]
}
object Extractable {

  final case class ExtractError(msg: String) extends AnyVal

}