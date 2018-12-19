package tech.beshu.ror.acl.blocks.rules

import cats.Show
import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.Rule.Name
import tech.beshu.ror.acl.request.RequestContext

sealed trait Rule {
  def name: Name
  def `match`(context: RequestContext): Task[Boolean]
}

object Rule {
  final case class Name(value: String) extends AnyVal
  object Name {
    implicit val show: Show[Name] = Show.show(_.value)
  }

  trait AuthenticationRule extends Rule
  trait AuthorizationRule extends Rule
  trait RegularRule extends Rule
  trait MatchingAlwaysRule extends Rule {
    def process(context: RequestContext): Task[Unit]
    override def `match`(context: RequestContext): Task[Boolean] =
      process(context).map(_ => true)
  }
}
