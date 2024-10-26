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
package tech.beshu.ror.accesscontrol.blocks.definitions

import cats.{Eq, Show}
import tech.beshu.ror.accesscontrol.blocks.definitions.ProxyAuth.Name
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions.Item

final case class ProxyAuth(override val id: ProxyAuth#Id, userIdHeader: Header.Name)
  extends Item {

  override type Id = Name
  override val idShow: Show[Name] = Show.show(_.value)
}

object ProxyAuth {
  final case class Name(value: String) extends AnyVal

  implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
}