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
