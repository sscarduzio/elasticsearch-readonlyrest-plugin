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
package tech.beshu.ror.es.actions.wrappers._cat

import org.elasticsearch.action.Action

class RorWrappedCatActionType extends Action[RorWrappedCatResponse](RorWrappedCatActionType.name) {
  override def newResponse(): RorWrappedCatResponse = new RorWrappedCatResponse
}
object RorWrappedCatActionType {
  val name = "cat_action"
  val instance = new RorWrappedCatActionType()
}
