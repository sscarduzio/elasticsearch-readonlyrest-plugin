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
import org.scalactic.anyvals.PosInt
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.fls.FieldsPolicy
import tech.beshu.ror.utils.WildcardPatternGenerators.{genPatternAndInstance, genStarText}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class FieldsPolicyTests extends AnyWordSpec with ScalaCheckDrivenPropertyChecks {

  override implicit val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(
      minSuccessful = 10000,
      workers = PosInt.ensuringValid(Runtime.getRuntime.availableProcessors())
    )

  "A FieldMatcher" should {
    "work in whitelist mode" in {
      val matcher = policy(AccessMode.Whitelist, "it*re*bus1", "item.*Date")

      matcher.canKeep("itemresobus2resobus1") should be(true)
      matcher.canKeep("item.endDate") should be(true)
      matcher.canKeep("item") should be(true)
      matcher.canKeep("item.endDate.text") should be(false)
      matcher.canKeep("item.endDate1") should be(false)
    }
    "work in blacklist mode" in {
      val matcher = policy(AccessMode.Blacklist, "it*re*bus1", "item.*Date")

      matcher.canKeep("itemresobus2resobus1") should be(false)
      matcher.canKeep("item.endDate") should be(false)
      matcher.canKeep("item") should be(true)
      matcher.canKeep("item.endDate.text") should be(false)
      matcher.canKeep("item.endDate1") should be(true)
    }
    "match regex metacharacters in field names literally" when {
      "whitelist mode is used" in {
        val matcher = policy(AccessMode.Whitelist, "price(usd)", "a[b]", "x*y")

        matcher.canKeep("price(usd)") should be(true)
        matcher.canKeep("priceusd") should be(false)
        matcher.canKeep("a[b]") should be(true)
        matcher.canKeep("ab") should be(false)
        matcher.canKeep("x(1)y") should be(true)
        matcher.canKeep("xy") should be(true)
        matcher.canKeep("x") should be(false)
      }
      "blacklist mode is used" in {
        val matcher = policy(AccessMode.Blacklist, "price(usd)", "a[b]", "x*y", "n+.v?")

        matcher.canKeep("price(usd)") should be(false)
        matcher.canKeep("priceusd") should be(true)
        matcher.canKeep("a[b]") should be(false)
        matcher.canKeep("ab") should be(true)
        matcher.canKeep("x(1)y") should be(false)
        matcher.canKeep("xy") should be(false)
        matcher.canKeep("x") should be(true)
        matcher.canKeep("n+.v?") should be(false)
        matcher.canKeep("nn.v") should be(true)
      }
      "a field name is not a valid regex" in {
        policy(AccessMode.Whitelist, "a(b").canKeep("a(b") should be(true)
        policy(AccessMode.Blacklist, "a(b").canKeep("a(b") should be(false)
      }
    }
    "match `*` to any characters" when {
      "the field name contains a line terminator" in {
        policy(AccessMode.Blacklist, "secret*").canKeep("secret\nx") should be(false)
      }
    }
    "hide in blacklist mode and keep in whitelist mode each field name made from the configured name" in {
      // Each `*` becomes text without a dot, so the field has the same dot-separated parts as the configured name.
      val starText = genStarText.map(_.replace(".", "") + "a")
      forAll(genPatternAndInstance(singleCharWildcard = false, starText)) { case (pattern, field) =>
        whenever(pattern.nonEmpty) {
          policy(AccessMode.Whitelist, pattern).canKeep(field) should be(true)
          policy(AccessMode.Blacklist, pattern).canKeep(field) should be(false)
          policy(AccessMode.Blacklist, pattern).canKeep(s"$field.child") should be(false)
        }
      }
    }
  }

  private def policy(mode: AccessMode, fields: String*) =
    new FieldsPolicy(
      FieldsRestrictions(
        UniqueNonEmptyList.unsafeFrom(fields.map(f => DocumentField(NonEmptyString.unsafeFrom(f)))),
        mode
      )
    )

}
