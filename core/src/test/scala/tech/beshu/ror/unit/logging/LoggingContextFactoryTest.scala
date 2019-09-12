package tech.beshu.ror.unit.logging

import eu.timepit.refined.types.string.NonEmptyString
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{Matchers, WordSpec}
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.logging.{LoggingContextFactory, ObfuscatedHeaders}

class LoggingContextFactoryTest
  extends WordSpec
    with TableDrivenPropertyChecks
    with Matchers {
  private val customHeaderName = Header.Name(NonEmptyString.unsafeFrom("CustomHeader"))
  private val secretHeaderName = Header.Name(NonEmptyString.unsafeFrom("Secret"))

  private val basicHeader = Header(Header.Name.authorization, NonEmptyString.unsafeFrom("secretButAuth"))
  private val customHeader = Header(customHeaderName, NonEmptyString.unsafeFrom("business value"))
  private val secretHeader = Header(secretHeaderName, NonEmptyString.unsafeFrom("secret"))
  "LoggingContextFactory" should {
    "create Show[Header] instance" when {
      "no configuration is provided" in {
        val table = Table(("conf", "authorization", "custom", "secret"),
          (None, "Authorization=<OMITTED>", "CustomHeader=business value", "Secret=secret"),
          (Some(ObfuscatedHeaders(Set(Header.Name.authorization))), "Authorization=<OMITTED>", "CustomHeader=business value", "Secret=secret"),
          (Some(ObfuscatedHeaders(Set(Header.Name.authorization, secretHeaderName))), "Authorization=<OMITTED>", "CustomHeader=business value", "Secret=<OMITTED>"),
        )
        forAll(table) { (conf, authorization, custom, secret) =>
          LoggingContextFactory.create(conf).showHeader.show(basicHeader) shouldEqual authorization
          LoggingContextFactory.create(conf).showHeader.show(customHeader) shouldEqual custom
          LoggingContextFactory.create(conf).showHeader.show(secretHeader) shouldEqual secret
        }

      }
    }
  }

}
