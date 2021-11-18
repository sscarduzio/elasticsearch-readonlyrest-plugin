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
package tech.beshu.ror.mocks

import cats.data.NonEmptyList
import com.comcast.ip4s.{Cidr, Hostname}
import monix.eval.Task
import org.scalamock.scalatest.MockFactory
import tech.beshu.ror.accesscontrol.blocks.rules.base.HostnameResolver
import tech.beshu.ror.accesscontrol.domain.Address
import tech.beshu.ror.accesscontrol.domain.Address.Ip
import tech.beshu.ror.mocks.MockHostnameResolver.Behaviour.ResolveResult.{ResolvedIps, Unresolvable}
import tech.beshu.ror.mocks.MockHostnameResolver.Behaviour.{MockAlways, MockOnce, ResolveResult}

object MockHostnameResolver extends MockFactory {

  def create(scenario: NonEmptyList[Behaviour]): HostnameResolver = {
    val mockedResolver = mock[HostnameResolver]
    scenario.toList.foreach {
      case MockAlways(hostname, resolveResult) =>
        mock(mockedResolver, hostname, resolveResult)
      case MockOnce(hostname, resolveResult) =>
        mock(mockedResolver, hostname, resolveResult).once()
    }
    mockedResolver
  }

  private def mock(mockedResolver: HostnameResolver, hostname: String, resolveResult: ResolveResult) = {
    (mockedResolver.resolve _)
      .expects(Address.Name(Hostname(hostname).get))
      .returning(resolvedIpsFrom(resolveResult))
  }

  private def resolvedIpsFrom(resolveResult: ResolveResult) = resolveResult match {
    case ip: ResolvedIps => Task.now(Some(
      NonEmptyList
        .of(ip.value, ip.values: _*)
        .map(str => Ip(Cidr.fromString(str).get))
    ))
    case Unresolvable =>
      Task.now(None)
  }

  sealed trait Behaviour
  object Behaviour {
    final case class MockAlways(hostname: String, result: ResolveResult) extends Behaviour
    final case class MockOnce(hostname: String, result: ResolveResult) extends Behaviour

    sealed trait ResolveResult
    object ResolveResult {
      final case class ResolvedIps(value: String, values: String*) extends ResolveResult
      case object Unresolvable extends ResolveResult
    }
  }
}
