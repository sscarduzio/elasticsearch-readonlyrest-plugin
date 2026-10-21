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

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.parser.decode
import org.scalatest.Outcome
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.audit.configurable.AuditFieldValueDescriptorParser
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, KibanaAccess}
import tech.beshu.ror.accesscontrol.factory.decoders.common.kibanaAccessDecoder
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher.Matchable
import tech.beshu.ror.audit.utils.AuditSerializationHelper.AuditFieldValueDescriptor
import tech.beshu.ror.utils.ScalaOps.{toRootLowerCase, toRootUpperCase}
import tech.beshu.ror.utils.yaml.JsonPathOps

import java.util.Locale

// These tests run with a Turkish default locale: ./gradlew core:testWithTurkishLocale
// In tr_TR, "I".toLowerCase is "ı" (dotless i) and "i".toUpperCase is "İ" (dotted capital I).
// With another default locale, the tests are canceled, because they cannot find a locale-dependent conversion.
class TurkishLocaleTests extends AnyWordSpec with Matchers {

  override def withFixture(test: NoArgTest): Outcome = {
    assume(Locale.getDefault.getLanguage == "tr", "the JVM default locale is not Turkish")
    super.withFixture(test)
  }

  "The JVM" should {
    "use the Turkish case rules for the default locale" in {
      "I".toLowerCase should be("ı")
      "i".toUpperCase should be("İ")
    }
  }

  "The root case extensions" should {
    "convert the case of 'I' and 'i' independently of the default locale" in {
      "I_UNDERSTAND_AND_ACCEPT_ES_PATCHING".toRootLowerCase should be("i_understand_and_accept_es_patching")
      "is_matched".toRootUpperCase should be("IS_MATCHED")
    }
  }

  "An env variable name of a setting" should {
    "use the ASCII upper case of the setting path" in {
      val path = NonEmptyList.of("readonlyrest", "settings", "file_path").map(NonEmptyString.unsafeFrom)
      JsonPathOps.pathToEnvVarName(path) should be("ES_SETTING_READONLYREST_SETTINGS_FILE__PATH")
    }
  }

  "An audit field value placeholder" should {
    "be recognized in lower case" in {
      AuditFieldValueDescriptorParser.parse("{id}") should be(Right(AuditFieldValueDescriptor.Id))
      AuditFieldValueDescriptorParser.parse("{is_matched}") should be(Right(AuditFieldValueDescriptor.IsMatched))
    }
  }

  "A case-insensitive patterns matcher" should {
    "match a value in another case" in {
      val matchable = Matchable.matchable[String](identity, CaseSensitivity.Disabled)
      PatternsMatcher.create(List("INDEX_*"))(matchable).`match`("index_1") should be(true)
      PatternsMatcher.create(List("index_*"))(matchable).`match`("INDEX_1") should be(true)
    }
  }

  "The kibana access setting" should {
    "be decoded in upper case" in {
      decode[KibanaAccess]("\"ADMIN\"") should be(Right(KibanaAccess.Admin))
    }
  }

}
