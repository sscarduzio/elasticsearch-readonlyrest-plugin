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
package tech.beshu.ror.audit

class Headers(originMap: Map[String, Set[String]]) {

  private val mapWithLowerCaseKeys = originMap
    .foldLeft(Map.empty[String, Set[String]]) {
      case (acc, (key, values)) =>
        val headerValues = acc.get(key.toLowerCase()).toList.flatten.toSet ++ values
        acc + (key.toLowerCase -> headerValues)
    }

  def getValue(headerName: String): Option[Set[String]] = {
    mapWithLowerCaseKeys.get(headerName.toLowerCase())
  }

  def names: Set[String] = originMap.keys.toSet
}
