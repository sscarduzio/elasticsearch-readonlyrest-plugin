/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.accesscontrol.blocks.rules.tranport

import cats.data.{NonEmptyList, NonEmptySet, OptionT}
import cats.implicits._
import com.comcast.ip4s.interop.cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RegularRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.domain.Address
import tech.beshu.ror.accesscontrol.domain.Address.{Ip, Name}
import tech.beshu.ror.implicits.{addressShow, ipShow}
import tech.beshu.ror.utils.TaskOps._

import scala.util.Success

private [rules] abstract class BaseHostsRule(resolver: HostnameResolver)
  extends RegularRule with Logging {

  protected def checkAllowedAddresses(blockContext: BlockContext)
                                     (allowedAddresses: NonEmptySet[RuntimeMultiResolvableVariable[Address]],
                                      addressToCheck: Address): Task[Boolean] = {
    allowedAddresses
      .foldLeft(Task.now(false)) {
        case (result, host) =>
          result
            .flatMap {
              case true =>
                Task.now(true)
              case false =>
                host
                  .resolve(blockContext).toOption
                  .existsM(addresses => addresses.existsM(ipMatchesAddress(_, addressToCheck, blockContext)))
            }
      }
  }

  private def ipMatchesAddress(allowedHost: Address, address: Address, blockContext: BlockContext) = {
    val result = for {
      allowedHostIps <- OptionT(resolveToIps(allowedHost))
      addressIps <- OptionT(resolveToIps(address))
      isMatching = addressIps.exists(ip => allowedHostIps.exists(_.contains(ip)))
      _ = logger.debug(s"[${blockContext.requestContext.id.show}] address IPs [${address.show}] resolved to [${addressIps.show}], allowed addresses [${allowedHost.show}] resolved to [${allowedHostIps.show}], isMatching=$isMatching")
    } yield isMatching
    result.value.map(_.getOrElse(false))
  }

  private def resolveToIps(address: Address) =
    address match {
      case address: Address.Ip =>
        Task.now(Some(NonEmptyList.one(address)))
      case address: Address.Name =>
        resolver
          .resolve(address)
          .andThen {
            case Success(None) => logger.warn(s"Cannot resolve hostname: ${address.value.show}")
          }
    }
}

trait HostnameResolver {
  def resolve(hostname: Name): Task[Option[NonEmptyList[Ip]]]
}