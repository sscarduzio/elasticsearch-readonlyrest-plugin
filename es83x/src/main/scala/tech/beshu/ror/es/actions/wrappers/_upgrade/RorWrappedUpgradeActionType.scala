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
package tech.beshu.ror.es.actions.wrappers._upgrade

import org.elasticsearch.action.ActionType
import org.elasticsearch.common.io.stream.Writeable
import org.elasticsearch.rest.action.admin.indices.RestUpgradeActionDeprecated

class RorWrappedUpgradeActionType extends ActionType[RorWrappedUpgradeResponse](
  RorWrappedUpgradeActionType.name, RorWrappedUpgradeActionType.exceptionReader
)
object RorWrappedUpgradeActionType {
  val name = new RestUpgradeActionDeprecated().getName
  val instance = new RorWrappedUpgradeActionType()

  case object ArtificialUpgradeActionCannotBeTransported extends Exception

  private [_upgrade] def exceptionReader[A]: Writeable.Reader[A] = _ => throw ArtificialUpgradeActionCannotBeTransported
}
