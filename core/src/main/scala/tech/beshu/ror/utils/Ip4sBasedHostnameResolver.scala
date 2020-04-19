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
package tech.beshu.ror.utils

import cats.data.NonEmptyList
import com.comcast.ip4s.Cidr
import com.comcast.ip4s.interop.cats.HostnameResolver
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.HostnameResolver
import tech.beshu.ror.accesscontrol.domain.Address.{Ip, Name}

class Ip4sBasedHostnameResolver extends HostnameResolver {

  // fixme: (improvements) blocking resolving (shift to another EC)
  def resolve(hostname: Name): Task[Option[NonEmptyList[Ip]]] = {
    HostnameResolver.resolveAll[Task](hostname.value).map(_.map(_.map(ip => Ip(Cidr(ip, 32)))))
  }
}
