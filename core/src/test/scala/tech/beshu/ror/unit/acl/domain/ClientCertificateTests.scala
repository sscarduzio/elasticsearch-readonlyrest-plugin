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

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.{Extension, GeneralName, GeneralNames}
import org.bouncycastle.asn1.{ASN1Encodable, ASN1ObjectIdentifier, DERSequence, DERTaggedObject, DERUTF8String}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.{ClientCertificate, DistinguishedName, Rdn, SubjectAlternativeName}

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

class ClientCertificateTests extends AnyWordSpec {

  "A distinguished name" should {
    "be parsed into RDNs in the order they are written" in {
      val dn = dnFrom("CN=svc-logstash,OU=Services,DC=corp,DC=example,DC=com")

      dn.rdns should be(
        List(
          Rdn.of(List("cn" -> "svc-logstash")),
          Rdn.of(List("ou" -> "Services")),
          Rdn.of(List("dc" -> "corp")),
          Rdn.of(List("dc" -> "example")),
          Rdn.of(List("dc" -> "com"))
        )
      )
    }
    "unescape a separator appearing inside a value" in {
      val dn = dnFrom("""CN=Smith\, John,OU=People,DC=corp""")

      dn.valuesOf("CN") should be(List("Smith, John"))
    }
    "treat a multi-valued RDN as several values" in {
      val dn = dnFrom("CN=svc1,OU=ingest+OU=metrics,O=Corp")

      dn.valuesOf("OU") should be(List("ingest", "metrics"))
    }
    "collect the values of an attribute appearing in several RDNs" in {
      val dn = dnFrom("CN=jsmith,OU=Engineering,OU=EMEA,OU=Employees,DC=corp")

      dn.valuesOf("OU") should be(List("Engineering", "EMEA", "Employees"))
    }
    "match attribute names case-insensitively" in {
      val dn = dnFrom("cn=svc-logstash,ou=Services")

      dn.valuesOf("CN") should be(List("svc-logstash"))
      dn.valuesOf("Cn") should be(List("svc-logstash"))
    }
    "have no values for an attribute it doesn't carry" in {
      dnFrom("CN=svc-logstash").valuesOf("OU") should be(List.empty)
    }
    "keep its RFC 2253 rendering" in {
      val value = """CN=Smith\, John,OU=People"""

      dnFrom(value).value should be(value)
    }
    "not be parsable when malformed" in {
      DistinguishedName.from("not a distinguished name") should be(
        Left("Cannot parse 'not a distinguished name' as a distinguished name")
      )
    }
  }

  "A distinguished name comparison" should {
    "report a suffix match against a base it ends with" in {
      val dn = dnFrom("CN=svc-logstash,OU=Services,DC=corp,DC=example,DC=com")

      dn.endsWith(dnFrom("OU=Services,DC=corp,DC=example,DC=com")) should be(true)
      dn.endsWith(dnFrom("DC=corp,DC=example,DC=com")) should be(true)
    }
    "not report a suffix match against a base from another population" in {
      val dn = dnFrom("CN=John Smith,OU=People,DC=corp,DC=example,DC=com")

      dn.endsWith(dnFrom("OU=Services,DC=corp,DC=example,DC=com")) should be(false)
    }
    "not report a suffix match when the base matches somewhere other than the end" in {
      val dn = dnFrom("CN=svc,OU=Services,DC=corp,OU=Services,DC=other")

      dn.endsWith(dnFrom("OU=Services,DC=corp")) should be(false)
    }
    "ignore the case of attribute names" in {
      dnFrom("CN=svc,ou=Services").hasTheSameRdnsAs(dnFrom("cn=svc,OU=Services")) should be(true)
    }
    "not ignore the case of values" in {
      dnFrom("CN=svc").hasTheSameRdnsAs(dnFrom("CN=SVC")) should be(false)
    }
    "ignore the order of the attributes of a multi-valued RDN" in {
      dnFrom("CN=svc1,OU=ingest+OU=metrics").hasTheSameRdnsAs(
        dnFrom("CN=svc1,OU=metrics+OU=ingest")
      ) should be(true)
    }
  }

  "A client certificate" should {
    "expose its subject and issuer distinguished names" in {
      val clientCertificate = clientCertificateFrom(
        certificate(subject = "CN=svc-logstash,OU=Services", issuer = "CN=Corp Issuing CA,DC=corp")
      )

      clientCertificate.subjectDn.valuesOf("CN") should be(List("svc-logstash"))
      clientCertificate.issuerDn.valuesOf("CN") should be(List("Corp Issuing CA"))
    }
    "expose no subject alternative names when the certificate carries none" in {
      clientCertificateFrom(certificate(subject = "CN=svc-logstash")).subjectAlternativeNames should be(List.empty)
    }
    "expose DNS, email, URI and IP subject alternative names" in {
      val clientCertificate = clientCertificateFrom(
        certificate(
          subject = "CN=node-01",
          subjectAlternativeNames = List(
            new GeneralName(GeneralName.dNSName, "node-01.corp.example.com"),
            new GeneralName(GeneralName.dNSName, "node-01"),
            new GeneralName(GeneralName.rfc822Name, "ops@example.com"),
            new GeneralName(GeneralName.uniformResourceIdentifier, "https://example.com/node-01"),
            new GeneralName(GeneralName.iPAddress, "10.0.0.1")
          )
        )
      )

      clientCertificate.subjectAlternativeNames should contain theSameElementsAs List(
        SubjectAlternativeName.Dns("node-01.corp.example.com"),
        SubjectAlternativeName.Dns("node-01"),
        SubjectAlternativeName.Email("ops@example.com"),
        SubjectAlternativeName.Uri("https://example.com/node-01"),
        SubjectAlternativeName.Ip("10.0.0.1")
      )
    }
    "expose an Active Directory user principal name carried in an otherName" in {
      val clientCertificate = clientCertificateFrom(
        certificate(
          subject = "CN=John Smith,OU=People",
          subjectAlternativeNames = List(userPrincipalName("jsmith@corp.example.com"))
        )
      )

      clientCertificate.subjectAlternativeNames should be(
        List(SubjectAlternativeName.OtherName(SubjectAlternativeName.userPrincipalNameOid, "jsmith@corp.example.com"))
      )
    }
  }

  private def dnFrom(value: String) =
    DistinguishedName
      .from(value)
      .getOrElse(throw new IllegalArgumentException(s"Cannot parse '$value' as a distinguished name"))

  private def clientCertificateFrom(certificate: X509Certificate) =
    ClientCertificate.from(certificate).getOrElse(throw new IllegalStateException("Cannot read the certificate"))

  private def userPrincipalName(value: String) = {
    new GeneralName(
      GeneralName.otherName,
      new DERSequence(
        Array[ASN1Encodable](
          new ASN1ObjectIdentifier(SubjectAlternativeName.userPrincipalNameOid.value),
          new DERTaggedObject(true, 0, new DERUTF8String(value))
        )
      )
    )
  }

  private def certificate(
      subject: String,
      issuer: String = "CN=Test CA",
      subjectAlternativeNames: List[GeneralName] = List.empty
  ): X509Certificate = {
    val now = Instant.now()
    val builder = new JcaX509v3CertificateBuilder(
      new X500Name(issuer),
      BigInteger.valueOf(now.toEpochMilli),
      Date.from(now.minus(1, ChronoUnit.DAYS)),
      Date.from(now.plus(1, ChronoUnit.DAYS)),
      new X500Name(subject),
      keyPair.getPublic
    )
    subjectAlternativeNames match {
      case Nil  =>
      case sans => builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(sans.toArray))
    }
    new JcaX509CertificateConverter()
      .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate)))
  }

  private lazy val keyPair = {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()
  }

}
