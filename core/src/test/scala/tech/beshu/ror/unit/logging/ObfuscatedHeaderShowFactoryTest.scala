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
package tech.beshu.ror.unit.logging

import eu.timepit.refined.types.string.NonEmptyString
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.Header

class ObfuscatedHeaderShowFactoryTest
  extends AnyWordSpec
    with TableDrivenPropertyChecks
    with Matchers {
  import tech.beshu.ror.accesscontrol.show.logs._
  private val customHeaderName = Header.Name(NonEmptyString.unsafeFrom("CustomHeader"))
  private val secretHeaderName = Header.Name(NonEmptyString.unsafeFrom("Secret"))

  private val basicHeader = Header(Header.Name.rorAuthorization, NonEmptyString.unsafeFrom("secretButAuth"))
  private val customHeader = Header(customHeaderName, NonEmptyString.unsafeFrom("business value"))
  private val secretHeader = Header(secretHeaderName, NonEmptyString.unsafeFrom("secret"))
  private val capitalizedAuthorization = Header.Name(NonEmptyString.unsafeFrom("authorization"))
  "LoggingContextFactory" should {
    "create Show[Header] instance" when {
      "no configuration is provided" in {
        val table = Table(("conf", "authorization", "custom", "secret"),
          (Set.empty[Header.Name], "Authorization=secretButAuth", "CustomHeader=business value", "Secret=secret"),
          (Set(capitalizedAuthorization), "Authorization=<OMITTED>", "CustomHeader=business value", "Secret=secret"),
          (Set(Header.Name.rorAuthorization), "Authorization=<OMITTED>", "CustomHeader=business value", "Secret=secret"),
          (Set(Header.Name.rorAuthorization, secretHeaderName), "Authorization=<OMITTED>", "CustomHeader=business value", "Secret=<OMITTED>"),
        )
        forAll(table) { (conf, authorization, custom, secret) =>
          obfuscatedHeaderShow(conf).show(basicHeader) shouldEqual authorization
          obfuscatedHeaderShow(conf).show(customHeader) shouldEqual custom
          obfuscatedHeaderShow(conf).show(secretHeader) shouldEqual secret
        }

      }
    }
  }

}
