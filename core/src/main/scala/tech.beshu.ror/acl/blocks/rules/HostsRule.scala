package tech.beshu.ror.acl.blocks.rules

import java.net.{InetAddress, UnknownHostException}

import cats.data.NonEmptySet
import com.typesafe.scalalogging.StrictLogging
import cz.seznam.euphoria.shaded.guava.com.google.common.net.InetAddresses
import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.HostsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.requestcontext.RequestContext
import tech.beshu.ror.commons.aDomain.{Header, UnresolvedAddress}
import tech.beshu.ror.commons.domain.{IPMask, Value}

import scala.util.control.Exception._

class HostsRule(settings: Settings)
  extends RegularRule with StrictLogging {

  override def `match`(context: RequestContext): Task[Boolean] = Task.now {
    getXForwardedForHeaderValue(context.getHeaders) match {
      case Some(xForwardedHeaderValue) if settings.acceptXForwardedForHeader =>
        if (tryToMatchAddress(xForwardedHeaderValue, context)) true
        else tryToMatchAddress(context.getRemoteAddress, context)
      case _ =>
        tryToMatchAddress(context.getRemoteAddress, context)
    }
  }

  private def tryToMatchAddress(address: UnresolvedAddress, context: RequestContext): Boolean =
    settings
      .hosts
      .exists { host =>
        host
          .getValue(context)
          .exists(ipMatchesAddress(_, address))
      }

  private def getXForwardedForHeaderValue(headers: Set[Header]): Option[UnresolvedAddress] = {
    headers
      .find(_.name == "X-Forwarded-For")
      .flatMap { header =>
        Option(header.value)
          .flatMap(_.split(",").headOption)
          .map(UnresolvedAddress.apply)
      }
  }

  private def ipMatchesAddress(allowedHost: UnresolvedAddress, address: UnresolvedAddress): Boolean =
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

  def isInetAddressOrBlock(address: UnresolvedAddress): Boolean = {
    val slash = address.value.lastIndexOf('/')
    InetAddresses.isInetAddress(if (slash != -1) address.value.substring(0, slash) else address.value)
  }
}

object HostsRule {

  final case class Settings(hosts: NonEmptySet[Value[UnresolvedAddress]], acceptXForwardedForHeader: Boolean)

}
