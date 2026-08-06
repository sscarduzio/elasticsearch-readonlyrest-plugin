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
package tech.beshu.ror.unit.acl.blocks.rules.auth

import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.definitions.PkiDef
import tech.beshu.ror.accesscontrol.blocks.definitions.PkiDef.{ExtractionSpec, Scope}
import tech.beshu.ror.accesscontrol.domain.{
  ClientCertificate,
  DistinguishedName,
  Header,
  JavaRegex,
  SubjectAlternativeName
}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.nes

/** Certificates are built straight from the domain model here - the rules never see an X509Certificate, so
  * their tests need no keystores. Turning a real certificate into this model is covered by ClientCertificateTests.
  */
trait PkiTestSupport {

  protected def pki(
      name: String = "corporate_pki",
      scope: Scope = Scope.unrestricted,
      usersExtraction: ExtractionSpec = ExtractionSpec.SubjectDnAttribute(nes("CN")),
      groupsExtraction: Option[ExtractionSpec] = None
  ): PkiDef = PkiDef(PkiDef.Name(NonEmptyString.unsafeFrom(name)), scope, usersExtraction, groupsExtraction)

  protected def certificateWith(
      subject: String,
      issuer: String = "CN=Test CA",
      subjectAlternativeNames: List[SubjectAlternativeName] = List.empty
  ): ClientCertificate = ClientCertificate(dn(subject), dn(issuer), subjectAlternativeNames)

  protected def dn(value: String): DistinguishedName =
    DistinguishedName.from(value).getOrElse(throw new IllegalArgumentException(s"Cannot parse '$value'"))

  protected def regex(value: String): JavaRegex =
    JavaRegex.compile(value).getOrElse(throw new IllegalArgumentException(s"Cannot compile '$value'"))

  protected def requestContextWith(clientCertificate: Option[ClientCertificate], headers: Set[Header]) = {
    val requestContext = MockRequestContext.indices.withHeaders(headers)
    clientCertificate.map(requestContext.withClientCertificate).getOrElse(requestContext)
  }

}
