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
package tech.beshu.ror.unit.acl.domain

import cats.data.NonEmptyList
import eu.timepit.refined.auto._
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers
import tech.beshu.ror.accesscontrol.domain.TemplateNamePattern
import tech.beshu.ror.utils.TestsUtils.unsafeNes

class TemplateNamePatternTests extends AnyFreeSpecLike with Matchers {

  "A TemplateNamePattern's method" - {
    "findMostGenericTemplateNamePatten should" - {
      "allow find the most generic template name pattern among given templates" in {
        TemplateNamePattern
          .findMostGenericTemplateNamePatten(NonEmptyList.of(
            TemplateNamePattern("temp1"), TemplateNamePattern("temp2"), TemplateNamePattern("temp3")
          )) should be(TemplateNamePattern("temp*"))

        TemplateNamePattern
          .findMostGenericTemplateNamePatten(NonEmptyList.of(
            TemplateNamePattern("te*"), TemplateNamePattern("temp2"), TemplateNamePattern("temp3")
          )) should be(TemplateNamePattern("te*"))

        TemplateNamePattern
          .findMostGenericTemplateNamePatten(NonEmptyList.of(
            TemplateNamePattern("te*"), TemplateNamePattern("temp2"), TemplateNamePattern("aTemp")
          )) should be(TemplateNamePattern("*"))
      }
    }
  }
}
