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
package tech.beshu.ror.unit.acl.blocks

import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.RuleOrdering
import tech.beshu.ror.accesscontrol.blocks.rules.Rule

class RuleOrderingTests extends AnyWordSpec {

  "RuleOrdering" should {
    "place all auth rules before any non-auth rule (fixed-prefix invariant)" in {
      // Scala 3 mangles private vals in companion objects; name derived from javap on RuleOrdering$.class
      val field = RuleOrdering.getClass.getDeclaredField("tech$beshu$ror$accesscontrol$blocks$RuleOrdering$$$orderedListOrRuleType")
      field.setAccessible(true)
      val orderedTypes = field.get(RuleOrdering).asInstanceOf[Seq[Class[_ <: Rule]]]

      def isAuthRuleClass(cls: Class[_ <: Rule]): Boolean =
        classOf[Rule.AuthenticationRule].isAssignableFrom(cls) ||
          classOf[Rule.AuthorizationRule].isAssignableFrom(cls)

      val nonAuthSuffix = orderedTypes.dropWhile(isAuthRuleClass)
      val authRulesAfterNonAuth = nonAuthSuffix.filter(isAuthRuleClass)

      withClue(
        s"Auth rule(s) found after a non-auth rule in RuleOrdering: ${authRulesAfterNonAuth.map(_.getSimpleName).mkString(", ")}. " +
          s"Block.evaluateForMetadataRequest relies on auth rules forming a fixed prefix."
      ) {
        authRulesAfterNonAuth shouldBe empty
      }
    }
  }
}
