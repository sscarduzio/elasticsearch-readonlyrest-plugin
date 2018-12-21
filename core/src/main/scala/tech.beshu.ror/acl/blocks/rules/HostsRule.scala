package tech.beshu.ror.acl.blocks.rules

import java.net.{InetAddress, UnknownHostException}

import cats.data.NonEmptySet
import com.typesafe.scalalogging.StrictLogging
import cz.seznam.euphoria.shaded.guava.com.google.common.net.InetAddresses
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.HostsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.{RuleResult, RegularRule}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._
import tech.beshu.ror.commons.aDomain.Address
import tech.beshu.ror.commons.domain.{IPMask, Value}

import scala.util.control.Exception._

class HostsRule(settings: Settings)
  extends RegularRule with StrictLogging {

  override val name: Rule.Name = Rule.Name("hosts")

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task.now {
    requestContext.xForwardedForHeaderValue match {
      case Some(xForwardedHeaderValue) if settings.acceptXForwardedForHeader =>
        if (tryToMatchAddress(xForwardedHeaderValue, requestContext))
          RuleResult.Fulfilled(blockContext)
        else
          RuleResult.fromCondition(blockContext) {
            tryToMatchAddress(requestContext.remoteAddress, requestContext)
          }
      case _ =>
        RuleResult.fromCondition(blockContext) {
          tryToMatchAddress(requestContext.remoteAddress, requestContext)
        }
    }
  }

  private def tryToMatchAddress(address: Address, requestContext: RequestContext): Boolean =
    settings
      .allowedHosts
      .exists { host =>
        host
          .getValue(requestContext)
          .exists(ipMatchesAddress(_, address))
      }

  private def ipMatchesAddress(allowedHost: Address, address: Address): Boolean =
    catching(classOf[UnknownHostException])
      .opt {
        val allowedResolvedIp =
          if (!isInetAddressOrBlock(allowedHost)) {
            // Super-late DNS resolution
            InetAddress.getByName(allowedHost.value).getHostAddress
          } else {
            allowedHost.value
          }
        val ip = IPMask.getIPMask(allowedResolvedIp)
        ip.matches(address.value)
      } match {
      case Some(result) => result
      case None =>
        logger.warn("Cannot resolve configured host name! $allowedHost")
        false
    }

  def isInetAddressOrBlock(address: Address): Boolean = {
    val slash = address.value.lastIndexOf('/')
    InetAddresses.isInetAddress(if (slash != -1) address.value.substring(0, slash) else address.value)
  }
}

object HostsRule {

  final case class Settings(allowedHosts: NonEmptySet[Value[Address]],
                            acceptXForwardedForHeader: Boolean)

}
