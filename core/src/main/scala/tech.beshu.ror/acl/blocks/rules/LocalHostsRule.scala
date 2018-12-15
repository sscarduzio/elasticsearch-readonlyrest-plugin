package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.LocalHostsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.requestcontext.RequestContext
import tech.beshu.ror.commons.aDomain.Address
import tech.beshu.ror.commons.domain.Value

class LocalHostsRule(settings: Settings)
  extends RegularRule {

  override def `match`(context: RequestContext): Task[Boolean] = Task.now {
    settings
      .allowedAddresses
      .toSortedSet
      .flatMap(_.getValue(context))
      .contains(context.localAddress)
  }
}

object LocalHostsRule {

  final case class Settings(allowedAddresses: NonEmptySet[Value[Address]])

}