package tech.beshu.ror.acl.blocks.rules

import java.net.{Inet4Address, InetAddress, UnknownHostException}

import cats.implicits._
import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.IPMask
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.acl.blocks.rules.XForwardedForRule.Settings
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._
import tech.beshu.ror.acl.aDomain.Address

import scala.util.control.Exception._

// todo: check what has changed on upstream and apply changes
class XForwardedForRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = XForwardedForRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task {
    requestContext.xForwardedForHeaderValue match {
      case Some(address) if address === Address.unknown => Rejected
      case None => Rejected
      case Some(address) if matchAddress(address, requestContext, blockContext) => Fulfilled(blockContext)
      case Some(address) => ipMaskOf(address) match {
        case None => Rejected
        case Some(ip) =>
          RuleResult.fromCondition(blockContext) {
            settings.allowedIps.exists(_.matches(ip))
          }
      }
    }
  }

  private def matchAddress(address: Address, requestContext: RequestContext, blockContext: BlockContext): Boolean =
    settings
      .allowedAddresses
      .flatMap(_.getValue(requestContext.variablesResolver, blockContext).toOption)
      .contains(address)

  private def ipMaskOf(address: Address): Option[Inet4Address] = {
    catching(classOf[UnknownHostException]).opt {
      InetAddress.getByName(address.value).asInstanceOf[Inet4Address]
    }
  }
}

object XForwardedForRule {
  val name = Rule.Name("x_forwarded_for")

  final case class Settings private(allowedAddresses: Set[Value[Address]],
                                    allowedIps: Set[IPMask])

  object Settings {
    def createFromAllowedAddresses(allowedAddresses: NonEmptySet[Value[Address]]): Settings =
      new Settings(allowedAddresses.toSortedSet, Set.empty)

    def createFromAllowedIps(allowedIps: NonEmptySet[IPMask]): Settings =
      new Settings(Set.empty, allowedIps.toSortedSet)

    def createFrom(allowedAddresses: NonEmptySet[Value[Address]],
                   allowedIps: NonEmptySet[IPMask]): Settings =
      new Settings(allowedAddresses.toSortedSet, allowedIps.toSortedSet)

    def apply(allowedAddresses: NonEmptySet[Value[Address]],
              allowedIps: NonEmptySet[IPMask]): Settings =
      createFrom(allowedAddresses, allowedIps)
  }

}
