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
package tech.beshu.ror.unit.utils

import eu.timepit.refined.types.string.NonEmptyString
import org.scalatest.concurrent.TimeLimits
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.fls.FieldsPolicy
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.util.regex.Pattern
import scala.util.Random

// Compares FieldsPolicy with a glob reference: each literal goes through Pattern.quote, and `*` is `(?s).*`.
class FieldsPolicyPropertyTests extends AnyWordSpec with TimeLimits {

  // Regex metacharacters, `*`, `.`, line terminators and non-ASCII characters. The emoji is a surrogate pair.
  private val alphabet = ("ab*.^$|?+()[]{}\\- é日\n\r\u2028\u0085".map(_.toString) :+ "😀").toVector

  // Few symbols make repeated and overlapping literals frequent.
  private val smallAlphabet = "ab*.".map(_.toString).toVector

  "FieldsPolicy" should {
    "match like a quoted glob" in {
      val random = new Random(2332L)
      val cases = 150000
      (1 to cases).foreach { _ =>
        val symbols = if (random.nextBoolean()) alphabet else smallAlphabet
        val pattern = randomPattern(random, symbols)
        val field = randomField(random, symbols, pattern)
        List(AccessMode.Whitelist, AccessMode.Blacklist).foreach { mode =>
          withClue(s"mode=$mode pattern=${show(pattern)} field=${show(field)}: ") {
            policy(mode, pattern).canKeep(field) should be(expected(mode, pattern, field))
          }
        }
      }
    }
    "stay linear on patterns with many wildcards" in {
      val name = "a" * 10000
      failAfter(Span(2, Seconds)) {
        policy(AccessMode.Blacklist, "*a*a*a*a*a*b").canKeep(name) should be(true)
        policy(AccessMode.Blacklist, "*a*a*a*a*a*b").canKeep(name + "b") should be(false)
        policy(AccessMode.Blacklist, "*a*a*a*a*a*ab*").canKeep(name) should be(true)
        policy(AccessMode.Whitelist, s"*${"a" * 1000}b*").canKeep(name) should be(false)
      }
    }
  }

  private def randomString(random: Random, maxLength: Int, symbols: Vector[String]): String = {
    val length = random.nextInt(maxLength + 1)
    (1 to length).map(_ => symbols(random.nextInt(symbols.size))).mkString
  }

  private def randomPattern(random: Random, symbols: Vector[String]): String = {
    val maxLength = if (random.nextInt(20) == 0) 60 else 10
    val pattern = randomString(random, maxLength, symbols)
    if (pattern.isEmpty) "*" else pattern
  }

  // Half of the field names come from the pattern, with each `*` replaced, so that many cases match.
  private def randomField(random: Random, symbols: Vector[String], pattern: String): String = random.nextInt(4) match {
    case 0 | 1 =>
      pattern.flatMap {
        case '*' => randomString(random, if (random.nextInt(10) == 0) 1000 else 4, symbols.filterNot(_ == "*"))
        case c   => c.toString
      }
    case 2 =>
      val instance = pattern.replace("*", randomString(random, 3, symbols))
      if (instance.isEmpty) instance
      else {
        val index = random.nextInt(instance.length)
        instance.patch(index, symbols(random.nextInt(symbols.size)), random.nextInt(2))
      }
    case _ =>
      randomString(random, if (random.nextInt(20) == 0) 1000 else 10, symbols)
  }

  private def expected(mode: AccessMode, pattern: String, field: String): Boolean = {
    val patternParts = pattern.split("\\.").toList.map(globRegex)
    val fieldParts = field.split("\\.").toList
    def partsMatch = patternParts.zip(fieldParts).forall { case (p, f) => p.matcher(f).matches() }
    tech.beshu.ror.constants.FIELDS_ALWAYS_ALLOW.contains(field) || (mode match {
      case AccessMode.Whitelist => fieldParts.length <= patternParts.length && partsMatch
      case AccessMode.Blacklist => !(patternParts.length <= fieldParts.length && partsMatch)
    })
  }

  private def globRegex(part: String): Pattern =
    Pattern.compile(part.split("\\*", -1).map(Pattern.quote).mkString("(?s).*"))

  private def show(value: String) =
    value.flatMap(c => if (c < ' ' || c > '~') f"\\u${c.toInt}%04x" else c.toString).take(300)

  private def policy(mode: AccessMode, field: String) =
    new FieldsPolicy(
      FieldsRestrictions(UniqueNonEmptyList.of(DocumentField(NonEmptyString.unsafeFrom(field))), mode)
    )

}
