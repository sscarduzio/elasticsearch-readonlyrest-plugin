package tech.beshu.ror.acl.blocks.rules

import monix.eval.Task
import tech.beshu.ror.acl.request.RequestContext

sealed trait Rule {
  def `match`(context: RequestContext): Task[Boolean]
}

object Rule {
  trait AuthenticationRule extends Rule
  trait AuthorizationRule extends Rule
  trait RegularRule extends Rule
  trait MatchingAlwaysRule extends Rule {
    def process(context: RequestContext): Task[Unit]
    override def `match`(context: RequestContext): Task[Boolean] =
      process(context).map(_ => true)
  }
}
