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
package tech.beshu.ror.unit.acl.blocks.rules.utils

import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.blocks.rules.utils.TemplateMatcher
import tech.beshu.ror.accesscontrol.domain.TemplateNamePattern
import org.scalatest.Matchers._

class TemplateMatcherTests extends WordSpec {

  val matcher = new TemplateMatcher(Set(
    TemplateNamePattern.fromString("temp*").get,
    TemplateNamePattern.fromString("tempo*X").get,
    TemplateNamePattern.fromString("*1").get,
    TemplateNamePattern.fromString("*xyz*5").get,
  ))

  // todo: cleanup
  "A TemplateMatcher" in {
    val templStar = TemplateNamePattern.fromString("templ*").get
    val teStar = TemplateNamePattern.fromString("te*").get
    val temporaryStarXXX = TemplateNamePattern.fromString("temporary*XXX").get
    val d54321 = TemplateNamePattern.fromString("d*54321").get
    val xyzStar6 = TemplateNamePattern.fromString("xyz*6").get
    val test_Starxyz_5 = TemplateNamePattern.fromString("test_*xyz_5").get

    matcher.filterTemplateNamePatterns(Set(templStar)) should be (Set(templStar))
    matcher.filterTemplateNamePatterns(Set(teStar)) should be (Set.empty)
    matcher.filterTemplateNamePatterns(Set(temporaryStarXXX)) should be (Set(temporaryStarXXX))
    matcher.filterTemplateNamePatterns(Set(d54321)) should be (Set(d54321))
    matcher.filterTemplateNamePatterns(Set(xyzStar6)) should be (Set.empty)
    matcher.filterTemplateNamePatterns(Set(test_Starxyz_5)) should be (Set(test_Starxyz_5))

  }
}
