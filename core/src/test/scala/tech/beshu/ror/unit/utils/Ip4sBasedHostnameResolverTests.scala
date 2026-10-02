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
package tech.beshu.ror.unit.utils

import cats.effect.Resource
import com.comcast.ip4s.{Cidr, Dns, Hostname, IpAddress}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.Address
import tech.beshu.ror.accesscontrol.domain.Address.Ip
import tech.beshu.ror.utils.Ip4sBasedHostnameResolver

import scala.concurrent.duration.*
import scala.language.postfixOps

class Ip4sBasedHostnameResolverTests extends AnyWordSpec {

  "Ip4sBasedHostnameResolver" should {
    "resolve a host name to one-host IPs" when {
      "the host name has an IPv4 and an IPv6 address" in {
        val dns = dnsResolvingTo(IpAddress.fromString("127.0.0.1").get, IpAddress.fromString("::1").get)
        val resolved = new Ip4sBasedHostnameResolver(Resource.pure[Task, Dns[Task]](dns))
          .resolve(Address.Name(Hostname.fromString("example.com").get))
          .runSyncUnsafe(10 seconds)
          .map(_.toList)
          .getOrElse(List.empty)

        resolved should contain(Ip(Cidr(IpAddress.fromString("127.0.0.1").get, 32)))
        resolved should contain(Ip(Cidr(IpAddress.fromString("::1").get, 128)))
      }
    }
  }

  private def dnsResolvingTo(ips: IpAddress*): Dns[Task] = new Dns[Task] {
    override def resolve(hostname: Hostname): Task[IpAddress] = Task.now(ips.head)
    override def resolveOption(hostname: Hostname): Task[Option[IpAddress]] = Task.now(ips.headOption)
    override def resolveAll(hostname: Hostname): Task[List[IpAddress]] = Task.now(ips.toList)
    override def loopback: Task[IpAddress] = Task.now(IpAddress.fromString("127.0.0.1").get)
  }

}
