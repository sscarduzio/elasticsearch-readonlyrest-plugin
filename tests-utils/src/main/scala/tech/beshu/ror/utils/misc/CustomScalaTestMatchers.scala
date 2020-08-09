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
package tech.beshu.ror.utils.misc

import org.scalatest.matchers.{MatchResult, Matcher}
import ujson._

trait CustomScalaTestMatchers {

  def containKeyOrValue(expectedKeyOrValue: String): JsonContainsKeyOrValue =
    new JsonContainsKeyOrValue(expectedKeyOrValue)

  class JsonContainsKeyOrValue(expectedKeyOrValue: String) extends Matcher[Value] {

    override def apply(json: Value): MatchResult = {
      MatchResult(
        checkIfContainsExpectedKeyOrValue(json, expectedKeyOrValue),
        s"Json $json doesn't contain '$expectedKeyOrValue' key or value",
        s"Json $json contains '$expectedKeyOrValue' key or value",
      )
    }

    private def checkIfContainsExpectedKeyOrValue(json: Value, expectedKeyOrValue: String): Boolean = {
      json match {
        case Str(value) =>
          value == expectedKeyOrValue
        case Obj(map) =>
          map.keys.find(_ == expectedKeyOrValue) match {
            case Some(_) => true
            case None => map.values.exists(checkIfContainsExpectedKeyOrValue(_, expectedKeyOrValue))
          }
        case Arr(jsons) =>
          jsons.exists(checkIfContainsExpectedKeyOrValue(_, expectedKeyOrValue))
        case Num(_) | _: Bool | Null =>
          false
      }
    }
  }
}

object CustomScalaTestMatchers extends CustomScalaTestMatchers
