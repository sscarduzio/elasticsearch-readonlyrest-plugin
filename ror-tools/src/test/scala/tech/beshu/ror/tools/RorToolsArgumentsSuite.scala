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
package tech.beshu.ror.tools

import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

class RorToolsArgumentsSuite extends AnyWordSpec with Matchers with TableDrivenPropertyChecks {

  "An argument" should {
    "get a lower case option name and keep the case of its value" in {
      forAll(
        Table(
          ("argument", "normalized argument"),
          ("--ES-PATH", "--es-path"),
          ("--ES-PATH=/Opt/Elasticsearch", "--es-path=/Opt/Elasticsearch"),
          ("--ES-PATH:/Opt/Elasticsearch", "--es-path:/Opt/Elasticsearch"),
          ("--es-path=C:\\Program Files\\ES", "--es-path=C:\\Program Files\\ES"),
          ("--I_UNDERSTAND_AND_ACCEPT_ES_PATCHING=YES", "--i_understand_and_accept_es_patching=YES"),
          ("/Opt/Elasticsearch", "/Opt/Elasticsearch"),
          ("Patch", "Patch"),
        )
      ) { (argument, normalizedArgument) =>
        RorTools.withLowerCaseOptionName(argument) should be(normalizedArgument)
      }
    }
  }

}
