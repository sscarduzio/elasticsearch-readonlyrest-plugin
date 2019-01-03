package tech.beshu.ror.acl.blocks.rules

import java.net.{InetAddress, UnknownHostException}

import cats.data.NonEmptySet
import com.typesafe.scalalogging.StrictLogging
import cz.seznam.euphoria.shaded.guava.com.google.common.net.InetAddresses
import monix.eval.Task
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.blocks.rules.HostsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._
import tech.beshu.ror.commons.aDomain.Address
import tech.beshu.ror.commons.domain.IPMask

import scala.util.control.Exception._

class HostsRule(settings: Settings)
  extends RegularRule with StrictLogging {

  override val name: Rule.Name = HostsRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task.now {
    requestContext.xForwardedForHeaderValue match {
      case Some(xForwardedHeaderValue) if settings.acceptXForwardedForHeader =>
        if (tryToMatchAddress(xForwardedHeaderValue, requestContext, blockContext))
          RuleResult.Fulfilled(blockContext)
        else
          RuleResult.fromCondition(blockContext) {
            tryToMatchAddress(requestContext.remoteAddress, requestContext, blockContext)
          }
      case _ =>
        RuleResult.fromCondition(blockContext) {
          tryToMatchAddress(requestContext.remoteAddress, requestContext, blockContext)
        }
    }
  }

  private def tryToMatchAddress(address: Address,
                                requestContext: RequestContext,
                                blockContext: BlockContext): Boolean =
    settings
      .allowedHosts
      .exists { host =>
        host
          .getValue(requestContext.variablesResolver, blockContext)
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

  private def isInetAddressOrBlock(address: Address): Boolean = {
    val slash = address.value.lastIndexOf('/')
    InetAddresses.isInetAddress(if (slash != -1) address.value.substring(0, slash) else address.value)
  }
}

object HostsRule {

  val name: Rule.Name = Rule.Name("hosts")

  final case class Settings(allowedHosts: NonEmptySet[Value[Address]],
                            acceptXForwardedForHeader: Boolean)

}
