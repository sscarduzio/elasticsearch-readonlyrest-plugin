package tech.beshu.ror.acl.blocks.rules

import cats.data.{NonEmptyList, NonEmptySet, OptionT}
import cats.implicits._
import com.comcast.ip4s.interop.cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.aDomain.Address
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.utils.TaskOps._

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
                  .getValue(requestContext.variablesResolver, blockContext).toOption
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
