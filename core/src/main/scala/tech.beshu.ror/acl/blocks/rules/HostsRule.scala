package tech.beshu.ror.acl.blocks.rules

import java.net.{InetAddress, UnknownHostException}

import cats.data.NonEmptySet
import com.typesafe.scalalogging.StrictLogging
import cz.seznam.euphoria.shaded.guava.com.google.common.net.InetAddresses
import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.HostsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._
import tech.beshu.ror.commons.aDomain.Address
import tech.beshu.ror.commons.domain.{IPMask, Value}

import scala.util.control.Exception._

class HostsRule(settings: Settings)
  extends RegularRule with StrictLogging {

  override def `match`(context: RequestContext): Task[Boolean] = Task.now {
    context.xForwardedForHeaderValue match {
      case Some(xForwardedHeaderValue) if settings.acceptXForwardedForHeader =>
        if (tryToMatchAddress(xForwardedHeaderValue, context)) true
        else tryToMatchAddress(context.remoteAddress, context)
      case _ =>
        tryToMatchAddress(context.remoteAddress, context)
    }
  }

  private def tryToMatchAddress(address: Address, context: RequestContext): Boolean =
    settings
      .allowedHosts
      .exists { host =>
        host
          .getValue(context)
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
