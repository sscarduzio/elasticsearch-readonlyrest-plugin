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

import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.providers.PropertiesProvider

class TestsPropertiesProvider(propertiesMap: Map[PropertiesProvider.PropName, String]) extends PropertiesProvider {
  override def getProperty(name: PropertiesProvider.PropName): Option[String] = propertiesMap.get(name)
}
object TestsPropertiesProvider {
  def default: TestsPropertiesProvider = new TestsPropertiesProvider(Map.empty)
  def usingMap(map: Map[String, String]) = new TestsPropertiesProvider(
    map.map { case (key, value) => (PropertiesProvider.PropName(NonEmptyString.unsafeFrom(key)), value) }
  )
}
