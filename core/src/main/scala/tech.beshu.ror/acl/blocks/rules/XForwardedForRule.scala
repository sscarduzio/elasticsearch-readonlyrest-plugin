package tech.beshu.ror.acl.blocks.rules

import java.net.{Inet4Address, InetAddress, UnknownHostException}

import cats.implicits._
import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.blocks.rules.XForwardedForRule.Settings
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._
import tech.beshu.ror.commons.aDomain.Address
import tech.beshu.ror.commons.domain.{IPMask, Value}
import scala.util.control.Exception._

class XForwardedForRule(settings: Settings)
  extends RegularRule {

  override def `match`(context: RequestContext): Task[Boolean] = Task.now {
    context.xForwardedForHeaderValue match {
      case Some(address) if address === Address.unknown => false
      case None => false
      case Some(address) if matchAddress(address, context) => true
      case Some(address) => ipMaskOf(address) match {
        case None => false
        case Some(ip) => settings.allowedIps.exists(_.matches(ip))
      }
    }
  }

  private def matchAddress(address: Address, context: RequestContext): Boolean =
    settings
      .allowedAddresses
      .flatMap(_.getValue(context))
      .contains(address)

  private def ipMaskOf(address: Address): Option[Inet4Address] = {
    catching(classOf[UnknownHostException]).opt {
      InetAddress.getByName(address.value).asInstanceOf[Inet4Address]
    }
  }
}

object XForwardedForRule {

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
