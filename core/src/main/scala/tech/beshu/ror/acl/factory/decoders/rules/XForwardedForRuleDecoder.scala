package tech.beshu.ror.acl.factory.decoders.rules

import cats.data.NonEmptySet
import cats.implicits._
import com.google.common.net.InetAddresses
import tech.beshu.ror.IPMask
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.blocks.Value._
import tech.beshu.ror.acl.blocks.rules.XForwardedForRule
import tech.beshu.ror.acl.blocks.rules.XForwardedForRule.Settings
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.factory.decoders.rules.XForwardedForRuleDecoderHelper.isInetAddressOrBlock
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.acl.aDomain.Address
import tech.beshu.ror.acl.orders._

import scala.collection.SortedSet
import scala.util.{Failure, Success, Try}

object XForwardedForRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet(identity)
    .emap { stringHosts =>
      val (addressesSet, ipsSet, errorsSet) =
        stringHosts.foldLeft((Set.empty[Value[Address]], Set.empty[IPMask], Set.empty[String])) {
          case ((addresses, ips, errors), stringHost) =>
            if (!isInetAddressOrBlock(stringHost)) {
              val address = Value.fromString(stringHost, rv => Right(Address(rv.value)))
                .getOrElse(throw new IllegalStateException("Address creation"))
              (addresses + address, ips, errors)
            } else {
              Try(IPMask.getIPMask(stringHost)) match {
                case Success(ip) => (addresses, ips + ip, errors)
                case Failure(_) => (addresses, ips, errors + s"Cannot get IP Mask from $stringHost")
              }
            }
        }
      if(errorsSet.nonEmpty) Left(errorsSet.mkString(","))
      else {
        (NonEmptySet.fromSet(SortedSet.empty[Value[Address]] ++ addressesSet), NonEmptySet.fromSet(SortedSet.empty[IPMask] ++ ipsSet)) match {
          case (Some(addresses), Some(ips)) => Right(Settings.createFrom(addresses, ips))
          case (Some(addresses), None) => Right(Settings.createFromAllowedAddresses(addresses))
          case (None, Some(ips)) => Right(Settings.createFromAllowedIps(ips))
          case (None, None) => Left("No addresses defined")
        }
      }
    }
    .map(new XForwardedForRule(_))
)

private object XForwardedForRuleDecoderHelper {

  def isInetAddressOrBlock(addressStr: String): Boolean = {
    val indexOfSlash = addressStr.indexOf('/')
    InetAddresses.isInetAddress {
      if (indexOfSlash != -1) addressStr.substring(0, indexOfSlash)
      else addressStr
    }
  }
}
