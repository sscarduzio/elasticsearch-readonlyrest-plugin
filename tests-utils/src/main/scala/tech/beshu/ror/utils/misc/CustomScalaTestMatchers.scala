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

import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers
import org.scalatest.matchers.{MatchResult, Matcher}
import tech.beshu.ror.utils.elasticsearch.BaseManager
import ujson.*

import scala.language.implicitConversions
import scala.util.matching.Regex

trait CustomScalaTestMatchers extends Matchers {

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

  implicit class UuidRegex(val context: ResultOfFullyMatchWordForString) {

    def uuidRegex: Assertion = {
      context regex """^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"""
    }
  }

  class SetMayNotContainsElementsMatcher(elements: Set[Regex]) extends Matcher[Set[String]] {

    override def apply(setToCheck: Set[String]): MatchResult = {
      MatchResult(
        doesAllValuesFromSetDontMatchAnyOfPatterns(setToCheck),
        s"Set [${setToCheck.mkString(",")}] contains elements which do match at least one of patterns [${elements.map(_.pattern.pattern()).mkString(",")}]",
        s"Set [${setToCheck.mkString(",")}] contains only elements matching which don't match any of patterns [${elements.map(_.pattern.pattern()).mkString(",")}]"
      )
    }

    private def doesAllValuesFromSetDontMatchAnyOfPatterns(setToCheck: Set[String]): Boolean = setToCheck.forall(value => !matchesAnyOfPatterns(value))

    private def matchesAnyOfPatterns(value: String) = elements.exists(r => r.findFirstMatchIn(value).isDefined)
  }

  def notContainElementsFrom(elements: Set[Regex]) = new SetMayNotContainsElementsMatcher(elements)

  class StatusCodeEquals(statusCode: Int) extends Matcher[BaseManager#SimpleResponse] {

    override def apply(response: BaseManager#SimpleResponse): MatchResult = {
      MatchResult(
        response.responseCode == statusCode,
        s"Expected status code was [$statusCode], but the response has [${response.responseCode}]; Moreover, body of the response was:\n${response.body}",
        s"Expected status code was [$statusCode] equals the response status code: [${response.responseCode}]; Moreover, body of the response was:\n${response.body}"
      )
    }
  }

  def haveStatusCode(statusCode: Int) = new StatusCodeEquals(statusCode)

  class HaveStatusCode[T <: BaseManager#SimpleResponse](val haveWord: ResultOfHaveWordForExtent[T])
    extends CustomScalaTestMatchers {
    import org.joor.Reflect.*

    def statusCode(statusCode: Int): Assertion = {
      val response = on(haveWord).get[T]("left")

      response should haveStatusCode(statusCode)
    }
  }

  implicit def toHaveWordStatusCode[T <: BaseManager#SimpleResponse](haveWord: ResultOfHaveWordForExtent[T]): HaveStatusCode[T] = {
    new HaveStatusCode(haveWord)
  }
}

object CustomScalaTestMatchers extends CustomScalaTestMatchers


