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

import cats.Show
import cats.data.{NonEmptyList, NonEmptySet, OptionT}
import com.comcast.ip4s.Host
import com.comcast.ip4s.Host.*
import monix.eval.Task
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RegularRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.domain.Address.{Ip, Name}
import tech.beshu.ror.accesscontrol.domain.{Address, RequestId}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.TaskOps.*

import scala.util.Success

private[rules] abstract class BaseHostsRule(resolver: HostnameResolver)
  extends RegularRule with RequestIdAwareLogging {

  protected def checkAllowedAddresses(blockContext: BlockContext)
                                     (allowedAddresses: NonEmptySet[RuntimeMultiResolvableVariable[Address]],
                                      addressToCheck: Address): Task[Boolean] = {
    implicit val requestId: RequestId = blockContext.requestContext.id.toRequestId
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

  private def ipMatchesAddress(allowedHost: Address,
                               address: Address,
                               blockContext: BlockContext)
                              (implicit requestId: RequestId)= {
    val parallelyResolved = Task.parMap2(resolveToIps(allowedHost), resolveToIps(address))(ParallellyResolvedIps.apply)
    val result = for {
      allowedHostIps <- OptionT(parallelyResolved.map(_.allowedHost))
      addressIps <- OptionT(parallelyResolved.map(_.address))
      isMatching = addressIps.exists(ip => allowedHostIps.exists(_.contains(ip)))
      _ = logger.debug(s"address IPs [${address.show}] resolved to [${addressIps.show}], allowed addresses [${allowedHost.show}] resolved to [${allowedHostIps.show}], isMatching=${isMatching.show}")(blockContext)
    } yield isMatching
    result.value.map(_.getOrElse(false))
  }

  private sealed case class ParallellyResolvedIps(allowedHost: Option[NonEmptyList[Ip]], address: Option[NonEmptyList[Ip]])

  private def resolveToIps(address: Address)
                          (implicit requestId: RequestId)=
    address match {
      case address: Address.Ip =>
        Task.now(Some(NonEmptyList.one(address)))
      case address: Address.Name =>
        resolver
          .resolve(address)
          .andThen {
            case Success(None) => logger.warn(s"Cannot resolve hostname: ${Show[Host].show(address.value)}")
          }
    }
}

trait HostnameResolver {
  def resolve(hostname: Name): Task[Option[NonEmptyList[Ip]]]
}