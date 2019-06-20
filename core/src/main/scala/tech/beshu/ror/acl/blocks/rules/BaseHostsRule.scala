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
package tech.beshu.ror.acl.blocks.rules

import cats.data.{NonEmptyList, NonEmptySet, OptionT}
import cats.implicits._
import com.comcast.ip4s.interop.cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.domain.Address
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.utils.TaskOps._

import scala.util.Success

abstract class BaseHostsRule extends RegularRule with Logging {

  protected def checkAllowedAddresses(requestContext: RequestContext,
                                      blockContext: BlockContext)
                                     (allowedAddresses: NonEmptySet[Value[Address]],
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
                  .get(requestContext.variablesResolver, blockContext).toOption
                  .existsM(ipMatchesAddress(_, addressToCheck))
            }
      }
  }

  private def ipMatchesAddress(allowedHost: Address, address: Address) = {
    val result = for {
      allowedHostIps <- OptionT(resolveToIps(allowedHost))
      addressIps <- OptionT(resolveToIps(address))
    } yield addressIps.exists(ip => allowedHostIps.exists(_.contains(ip)))
    result.value.map(_.getOrElse(false))
  }

  private def resolveToIps(address: Address) =
    address match {
      case address: Address.Ip =>
        Task.now(Some(NonEmptyList.one(address)))
      case address: Address.Name =>
        Address
          .resolve(address)
          .andThen {
            case Success(None) => logger.warn(s"Cannot resolve hostname: ${address.value.show}")
          }
    }
}
