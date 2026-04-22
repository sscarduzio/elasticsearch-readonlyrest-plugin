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
package tech.beshu.ror.unit.utils.yaml

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Json
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import squants.information.Kilobytes
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.utils.FromString
import tech.beshu.ror.utils.TestsPropertiesProvider
import tech.beshu.ror.utils.yaml.{YamlLeafOrPropertyDecoder, YamlParser}

class YamlLeafOrPropertyDecoderTest extends AnyWordSpec {

  private val yamlParser = new YamlParser(Some(Kilobytes(100)))

  "createOptionalValueDecoder" should {
    "decode a value from YAML when only YAML has it" in {
      val json = parse(
        """
          |readonlyrest:
          |  ssl:
          |    enable: true
          |""".stripMargin
      )

      given PropertiesProvider = TestsPropertiesProvider.default
      val decoder = YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = path("readonlyrest", "ssl", "enable"),
        decoder = FromString.boolean
      )

      decoder.decode(json) should be(Right(Some(true)))
    }
    "decode a value from property when only property has it" in {
      val json = parse("node.name: n1")

      given PropertiesProvider = TestsPropertiesProvider.usingMap(Map(
        "readonlyrest.ssl.enable" -> "true"
      ))
      val decoder = YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = path("readonlyrest", "ssl", "enable"),
        decoder = FromString.boolean
      )

      decoder.decode(json) should be(Right(Some(true)))
    }
    "return None when absent in both YAML and property" in {
      val json = parse("node.name: n1")

      given PropertiesProvider = TestsPropertiesProvider.default
      val decoder = YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = path("readonlyrest", "ssl", "enable"),
        decoder = FromString.boolean
      )

      decoder.decode(json) should be(Right(None))
    }
    "fail when the value is defined in both YAML and property" in {
      val json = parse(
        """
          |readonlyrest:
          |  ssl:
          |    enable: true
          |""".stripMargin
      )

      given PropertiesProvider = TestsPropertiesProvider.usingMap(Map(
        "readonlyrest.ssl.enable" -> "false"
      ))
      val decoder = YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = path("readonlyrest", "ssl", "enable"),
        decoder = FromString.boolean
      )

      decoder.decode(json) should be(Left(
        "Value at '.readonlyrest.ssl.enable' is defined in both YAML configuration and system properties. Please use only one."
      ))
    }
  }

  "createRequiredValueDecoder" should {
    "decode a value from YAML when only YAML has it" in {
      val json = parse(
        """
          |readonlyrest:
          |  ssl:
          |    keystore_file: ror.jks
          |""".stripMargin
      )

      given PropertiesProvider = TestsPropertiesProvider.default
      val decoder = YamlLeafOrPropertyDecoder.createRequiredValueDecoder(
        path = path("readonlyrest", "ssl", "keystore_file"),
        decoder = FromString.string
      )

      decoder.decode(json) should be(Right("ror.jks"))
    }
    "decode a value from property when only property has it" in {
      val json = parse("node.name: n1")

      given PropertiesProvider = TestsPropertiesProvider.usingMap(Map(
        "readonlyrest.ssl.keystore_file" -> "ror.jks"
      ))
      val decoder = YamlLeafOrPropertyDecoder.createRequiredValueDecoder(
        path = path("readonlyrest", "ssl", "keystore_file"),
        decoder = FromString.string
      )

      decoder.decode(json) should be(Right("ror.jks"))
    }
    "fail when absent in both YAML and property" in {
      val json = parse("node.name: n1")

      given PropertiesProvider = TestsPropertiesProvider.default
      val decoder = YamlLeafOrPropertyDecoder.createRequiredValueDecoder(
        path = path("readonlyrest", "ssl", "keystore_file"),
        decoder = FromString.string
      )

      decoder.decode(json) should be(Left("Cannot find '.readonlyrest.ssl.keystore_file' path"))
    }
    "fail when the value is defined in both YAML and property" in {
      val json = parse(
        """
          |readonlyrest:
          |  ssl:
          |    keystore_file: ror.jks
          |""".stripMargin
      )

      given PropertiesProvider = TestsPropertiesProvider.usingMap(Map(
        "readonlyrest.ssl.keystore_file" -> "other.jks"
      ))
      val decoder = YamlLeafOrPropertyDecoder.createRequiredValueDecoder(
        path = path("readonlyrest", "ssl", "keystore_file"),
        decoder = FromString.string
      )

      decoder.decode(json) should be(Left(
        "Value at '.readonlyrest.ssl.keystore_file' is defined in both YAML configuration and system properties. Please use only one."
      ))
    }
  }

  "createOptionalListValueDecoder" should {
    "decode a YAML sequence" in {
      val json = parse(
        """
          |readonlyrest:
          |  ssl:
          |    allowed_ciphers:
          |      - TLS_RSA_WITH_AES_128_CBC_SHA
          |      - TLS_RSA_WITH_AES_256_CBC_SHA
          |""".stripMargin
      )

      given PropertiesProvider = TestsPropertiesProvider.default
      val decoder = YamlLeafOrPropertyDecoder.createOptionalListValueDecoder(
        path = path("readonlyrest", "ssl", "allowed_ciphers"),
        itemDecoder = FromString.string
      )

      decoder.decode(json) should be(Right(Some(Set("TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA"))))
    }
    "decode a comma-separated string as a list" in {
      val json = parse(
        """
          |readonlyrest:
          |  ssl:
          |    allowed_ciphers: "TLS_RSA_WITH_AES_128_CBC_SHA,TLS_RSA_WITH_AES_256_CBC_SHA"
          |""".stripMargin
      )

      given PropertiesProvider = TestsPropertiesProvider.default
      val decoder = YamlLeafOrPropertyDecoder.createOptionalListValueDecoder(
        path = path("readonlyrest", "ssl", "allowed_ciphers"),
        itemDecoder = FromString.string
      )

      decoder.decode(json) should be(Right(Some(Set("TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA"))))
    }
    "return None when the path is absent" in {
      val json = parse("node.name: n1")

      given PropertiesProvider = TestsPropertiesProvider.default
      val decoder = YamlLeafOrPropertyDecoder.createOptionalListValueDecoder(
        path = path("readonlyrest", "ssl", "allowed_ciphers"),
        itemDecoder = FromString.string
      )

      decoder.decode(json) should be(Right(None))
    }
    "use a JVM property as a comma-separated list when YAML path is absent" in {
      val json = parse("node.name: n1")

      given PropertiesProvider = TestsPropertiesProvider.usingMap(Map(
        "readonlyrest.ssl.allowed_ciphers" -> "TLS_A,TLS_B"
      ))
      val decoder = YamlLeafOrPropertyDecoder.createOptionalListValueDecoder(
        path = path("readonlyrest", "ssl", "allowed_ciphers"),
        itemDecoder = FromString.string
      )

      decoder.decode(json) should be(Right(Some(Set("TLS_A", "TLS_B"))))
    }
    "fail when the value is defined in both YAML and property" in {
      val json = parse(
        """
          |readonlyrest:
          |  ssl:
          |    allowed_ciphers:
          |      - TLS_RSA_WITH_AES_128_CBC_SHA
          |""".stripMargin
      )

      given PropertiesProvider = TestsPropertiesProvider.usingMap(Map(
        "readonlyrest.ssl.allowed_ciphers" -> "TLS_A,TLS_B"
      ))
      val decoder = YamlLeafOrPropertyDecoder.createOptionalListValueDecoder(
        path = path("readonlyrest", "ssl", "allowed_ciphers"),
        itemDecoder = FromString.string
      )

      decoder.decode(json) should be(Left(
        "Value at '.readonlyrest.ssl.allowed_ciphers' is defined in both YAML configuration and system properties. Please use only one."
      ))
    }
  }

  "whenSectionPresent" should {
    "return None without evaluating the inner decoder when section is absent" in {
      val json = parse("node.name: n1")

      given PropertiesProvider = TestsPropertiesProvider.default
      val inner = YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = path("readonlyrest", "ssl", "enable"),
        decoder = FromString.boolean
      )
      val decoder = YamlLeafOrPropertyDecoder.whenSectionPresent(path("readonlyrest", "ssl"))(inner)

      decoder.decode(json) should be(Right(None))
    }
    "evaluate the inner decoder when the section is present" in {
      val json = parse(
        """
          |readonlyrest:
          |  ssl:
          |    enable: true
          |""".stripMargin
      )

      given PropertiesProvider = TestsPropertiesProvider.default
      val inner = YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = path("readonlyrest", "ssl", "enable"),
        decoder = FromString.boolean
      )
      val decoder = YamlLeafOrPropertyDecoder.whenSectionPresent(path("readonlyrest", "ssl"))(inner)

      decoder.decode(json) should be(Right(Some(true)))
    }
    "return None when the section exists but the inner path is absent" in {
      val json = parse(
        """
          |readonlyrest:
          |  ssl:
          |    keystore_file: ror.jks
          |""".stripMargin
      )

      given PropertiesProvider = TestsPropertiesProvider.default
      val inner = YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = path("readonlyrest", "ssl", "enable"),
        decoder = FromString.boolean
      )
      val decoder = YamlLeafOrPropertyDecoder.whenSectionPresent(path("readonlyrest", "ssl"))(inner)

      decoder.decode(json) should be(Right(None))
    }
    "evaluate the inner decoder when the section is absent from YAML but a property under it exists" in {
      val json = parse("node.name: n1")

      given PropertiesProvider = TestsPropertiesProvider.usingMap(Map(
        "readonlyrest.ssl.enable" -> "true"
      ))
      val inner = YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = path("readonlyrest", "ssl", "enable"),
        decoder = FromString.boolean
      )
      val decoder = YamlLeafOrPropertyDecoder.whenSectionPresent(path("readonlyrest", "ssl"))(inner)

      decoder.decode(json) should be(Right(Some(true)))
    }
    "return None when the section is absent from both YAML and properties" in {
      val json = parse("node.name: n1")

      given PropertiesProvider = TestsPropertiesProvider.default
      val inner = YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = path("readonlyrest", "ssl", "enable"),
        decoder = FromString.boolean
      )
      val decoder = YamlLeafOrPropertyDecoder.whenSectionPresent(path("readonlyrest", "ssl"))(inner)

      decoder.decode(json) should be(Right(None))
    }
  }

  "createLegacyPropertyDecoder" should {
    "return the value from the JVM property" in {
      given PropertiesProvider = TestsPropertiesProvider.usingMap(Map(
        "com.readonlyrest.settings.refresh.interval" -> "30s"
      ))
      val decoder = YamlLeafOrPropertyDecoder.createLegacyPropertyDecoder(
        legacyKey = NonEmptyString.unsafeFrom("com.readonlyrest.settings.refresh.interval"),
        decoder = FromString.string
      )

      decoder.decode(Json.Null) should be(Right(Some("30s")))
    }
    "return None when the JVM property is absent" in {
      given PropertiesProvider = TestsPropertiesProvider.default
      val decoder = YamlLeafOrPropertyDecoder.createLegacyPropertyDecoder(
        legacyKey = NonEmptyString.unsafeFrom("com.readonlyrest.settings.refresh.interval"),
        decoder = FromString.string
      )

      decoder.decode(Json.Null) should be(Right(None))
    }
    "return a decoding error when the property value is invalid" in {
      given PropertiesProvider = TestsPropertiesProvider.usingMap(Map(
        "com.readonlyrest.settings.refresh.interval" -> "not-a-duration"
      ))
      val decoder = YamlLeafOrPropertyDecoder.createLegacyPropertyDecoder(
        legacyKey = NonEmptyString.unsafeFrom("com.readonlyrest.settings.refresh.interval"),
        decoder = FromString.nonNegativeFiniteDuration
      )

      decoder.decode(Json.Null) should be(Left(
        "Cannot parse 'not-a-duration' as a duration. Expected a finite duration like '5s', '1m'"
      ))
    }
  }

  "orElse" should {
    "use the second decoder when the first returns None" in {
      val json = parse("node.name: n1")

      given PropertiesProvider = TestsPropertiesProvider.usingMap(Map(
        "com.readonlyrest.settings.refresh.interval" -> "20s"
      ))
      val first = YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = path("readonlyrest", "load_from_index", "poll_interval"),
        decoder = FromString.string
      )
      val second = YamlLeafOrPropertyDecoder.createLegacyPropertyDecoder(
        legacyKey = NonEmptyString.unsafeFrom("com.readonlyrest.settings.refresh.interval"),
        decoder = FromString.string
      )

      first.orElse(second).decode(json) should be(Right(Some("20s")))
    }
    "prefer the first decoder when it returns a value" in {
      val json = parse(
        """
          |readonlyrest:
          |  load_from_index:
          |    poll_interval: 5s
          |""".stripMargin
      )

      given PropertiesProvider = TestsPropertiesProvider.usingMap(Map(
        "com.readonlyrest.settings.refresh.interval" -> "20s"
      ))
      val first = YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = path("readonlyrest", "load_from_index", "poll_interval"),
        decoder = FromString.string
      )
      val second = YamlLeafOrPropertyDecoder.createLegacyPropertyDecoder(
        legacyKey = NonEmptyString.unsafeFrom("com.readonlyrest.settings.refresh.interval"),
        decoder = FromString.string
      )

      first.orElse(second).decode(json) should be(Right(Some("5s")))
    }
  }

  private def parse(yaml: String): Json =
    yamlParser.parse(yaml).toTry.get

  private def path(head: String, rest: String*): NonEmptyList[NonEmptyString] =
    NonEmptyList(NonEmptyString.unsafeFrom(head), rest.toList.map(NonEmptyString.unsafeFrom))
}
