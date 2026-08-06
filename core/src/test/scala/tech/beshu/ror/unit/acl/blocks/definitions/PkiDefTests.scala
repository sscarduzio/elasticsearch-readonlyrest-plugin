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
package tech.beshu.ror.unit.acl.blocks.definitions

import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.definitions.PkiDef.{ExtractionSpec, SanType, Scope}
import tech.beshu.ror.accesscontrol.domain.{
  ClientCertificate,
  DistinguishedName,
  JavaRegex,
  Oid,
  SubjectAlternativeName
}
import tech.beshu.ror.utils.TestsUtils.nes

class PkiDefTests extends AnyWordSpec {

  "Reading a subject DN attribute" should {
    "yield the value of the named attribute" in {
      ExtractionSpec
        .SubjectDnAttribute(nes("CN"))
        .extractFrom(certificateWith(subject = "CN=svc-logstash,OU=Services,DC=corp")) should be(List("svc-logstash"))
    }
    "yield every value when the attribute appears more than once" in {
      ExtractionSpec
        .SubjectDnAttribute(nes("OU"))
        .extractFrom(certificateWith(subject = "CN=jsmith,OU=Engineering,OU=EMEA,DC=corp")) should be(
        List("Engineering", "EMEA")
      )
    }
    "yield nothing when the attribute is absent" in {
      ExtractionSpec
        .SubjectDnAttribute(nes("UID"))
        .extractFrom(certificateWith(subject = "CN=svc-logstash")) should be(List.empty)
    }
  }

  "Reading the subject DN with a pattern" should {
    "yield the captured value" in {
      ExtractionSpec
        .SubjectDnPattern(regex("^CN=([^,]+),OU=Service Accounts,.*$"))
        .extractFrom(certificateWith(subject = "CN=svc-logstash,OU=Service Accounts,DC=corp")) should be(
        List("svc-logstash")
      )
    }
    "yield every match, which is what turns one pattern into several groups" in {
      ExtractionSpec
        .SubjectDnPattern(regex("OU=grp-([^,]+)"))
        .extractFrom(certificateWith(subject = "CN=svc,OU=grp-ingest,OU=grp-metrics,DC=corp")) should be(
        List("ingest", "metrics")
      )
    }
    "unescape a captured value, since patterns run against the escaped rendering" in {
      ExtractionSpec
        .SubjectDnPattern(regex("^CN=(.+),OU=People$"))
        .extractFrom(certificateWith(subject = """CN=Smith\, John,OU=People""")) should be(List("Smith, John"))
    }
    "yield nothing when the pattern doesn't match" in {
      ExtractionSpec
        .SubjectDnPattern(regex("^CN=([^,]+),OU=Service Accounts,.*$"))
        .extractFrom(certificateWith(subject = "CN=jsmith,OU=People,DC=corp")) should be(List.empty)
    }
  }

  "Reading a subject alternative name" should {
    "yield every entry of the requested type, so that a username takes the first" in {
      ExtractionSpec
        .San(SanType.Dns, pattern = None)
        .extractFrom(
          certificateWith(
            subject = "CN=node-01",
            subjectAlternativeNames = List(
              SubjectAlternativeName.Dns("node-01.corp.example.com"),
              SubjectAlternativeName.Dns("node-01"),
              SubjectAlternativeName.Email("ops@example.com")
            )
          )
        ) should be(List("node-01.corp.example.com", "node-01"))
    }
    "select among the entries when a pattern is given" in {
      ExtractionSpec
        .San(SanType.Dns, pattern = Some(regex("""^(.+)\.corp\.example\.com$""")))
        .extractFrom(
          certificateWith(
            subject = "CN=node-01",
            subjectAlternativeNames = List(
              SubjectAlternativeName.Dns("node-01"),
              SubjectAlternativeName.Dns("node-01.corp.example.com")
            )
          )
        ) should be(List("node-01"))
    }
    "yield the Active Directory user principal name" in {
      ExtractionSpec
        .San(SanType.Upn, pattern = None)
        .extractFrom(
          certificateWith(
            subject = "CN=John Smith,OU=People",
            subjectAlternativeNames = List(
              SubjectAlternativeName.Dns("workstation-7"),
              SubjectAlternativeName
                .OtherName(SubjectAlternativeName.userPrincipalNameOid, "jsmith@corp.example.com")
            )
          )
        ) should be(List("jsmith@corp.example.com"))
    }
    "ignore an otherName carrying a different object identifier" in {
      ExtractionSpec
        .San(SanType.Upn, pattern = None)
        .extractFrom(
          certificateWith(
            subject = "CN=John Smith",
            subjectAlternativeNames = List(
              SubjectAlternativeName.OtherName(Oid("1.2.3.4"), "something else")
            )
          )
        ) should be(List.empty)
    }
  }

  "The scope of a provider" should {
    "accept anything when unrestricted" in {
      Scope.unrestricted.accepts(certificateWith(subject = "CN=anyone,OU=anywhere")) should be(true)
    }
    "accept only certificates whose subject sits under the configured base" in {
      val scope = Scope(subjectDnBase = Some(dn("OU=Services,DC=corp,DC=example")), issuerDn = None)

      scope.accepts(certificateWith(subject = "CN=svc-logstash,OU=Services,DC=corp,DC=example")) should be(true)
      scope.accepts(certificateWith(subject = "CN=John Smith,OU=People,DC=corp,DC=example")) should be(false)
    }
    "accept only certificates minted by the configured issuer" in {
      val scope = Scope(subjectDnBase = None, issuerDn = Some(dn("CN=Corp Issuing CA,DC=corp")))

      scope.accepts(certificateWith(subject = "CN=svc", issuer = "CN=Corp Issuing CA,DC=corp")) should be(true)
      scope.accepts(certificateWith(subject = "CN=svc", issuer = "CN=Other CA,DC=corp")) should be(false)
    }
    "require the issuer to match in full, not merely to end with the configured name" in {
      val scope = Scope(subjectDnBase = None, issuerDn = Some(dn("DC=corp")))

      scope.accepts(certificateWith(subject = "CN=svc", issuer = "CN=Corp Issuing CA,DC=corp")) should be(false)
    }
    "require every constraint to hold" in {
      val scope = Scope(
        subjectDnBase = Some(dn("OU=Services,DC=corp")),
        issuerDn = Some(dn("CN=Corp Issuing CA,DC=corp"))
      )

      scope.accepts(
        certificateWith(subject = "CN=svc,OU=Services,DC=corp", issuer = "CN=Corp Issuing CA,DC=corp")
      ) should be(true)
      scope.accepts(
        certificateWith(subject = "CN=svc,OU=Services,DC=corp", issuer = "CN=Other CA,DC=corp")
      ) should be(false)
    }
  }

  private def certificateWith(
      subject: String,
      issuer: String = "CN=Test CA",
      subjectAlternativeNames: List[SubjectAlternativeName] = List.empty
  ) = ClientCertificate(dn(subject), dn(issuer), subjectAlternativeNames)

  private def dn(value: String) =
    DistinguishedName.from(value).getOrElse(throw new IllegalArgumentException(s"Cannot parse '$value'"))

  private def regex(value: String) =
    JavaRegex.compile(value).getOrElse(throw new IllegalArgumentException(s"Cannot compile '$value'"))
}
