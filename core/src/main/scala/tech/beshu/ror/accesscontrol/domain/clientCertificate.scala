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
package tech.beshu.ror.accesscontrol.domain

import javax.naming.ldap.LdapName
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.{
  ASN1Encodable,
  ASN1ObjectIdentifier,
  ASN1Primitive,
  ASN1Sequence,
  ASN1String,
  ASN1TaggedObject
}
import tech.beshu.ror.implicits.*

import java.security.cert.X509Certificate
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** A TLS client certificate the transport layer has already verified.
  *
  * Only the parts the ACL can derive an identity from are modelled; chain validation and validity dates
  * are the TLS layer's business and have been settled by the time an instance of this class exists.
  */
final case class ClientCertificate(
    subjectDn: DistinguishedName,
    issuerDn: DistinguishedName,
    subjectAlternativeNames: List[SubjectAlternativeName]
)

object ClientCertificate {

  def from(certificate: X509Certificate): Either[String, ClientCertificate] = {
    for {
      subjectDn <- DistinguishedName.from(certificate.getSubjectX500Principal)
      issuerDn <- DistinguishedName.from(certificate.getIssuerX500Principal)
    } yield ClientCertificate(
      subjectDn = subjectDn,
      issuerDn = issuerDn,
      subjectAlternativeNames = subjectAlternativeNamesOf(certificate)
    )
  }

  private def subjectAlternativeNamesOf(certificate: X509Certificate): List[SubjectAlternativeName] = {
    Try(Option(certificate.getSubjectAlternativeNames)).toOption.flatten
      .map(_.asScala.toList.flatMap(subjectAlternativeNameFrom))
      .getOrElse(List.empty)
  }

  // Each entry is a two-element list: the type tag, and either a String or (for otherName) the DER encoding.
  // See X509Certificate#getSubjectAlternativeNames.
  private def subjectAlternativeNameFrom(entry: java.util.List[?]): Option[SubjectAlternativeName] = {
    entry.asScala.toList match {
      case (tag: Integer) :: value :: _ =>
        (tag.intValue(), value) match {
          case (1, email: String)    => Some(SubjectAlternativeName.Email(email))
          case (2, dns: String)      => Some(SubjectAlternativeName.Dns(dns))
          case (6, uri: String)      => Some(SubjectAlternativeName.Uri(uri))
          case (7, ip: String)       => Some(SubjectAlternativeName.Ip(ip))
          case (0, der: Array[Byte]) => otherNameFrom(der)
          case _                     => None
        }
      case _ => None
    }
  }

  // OtherName ::= SEQUENCE { type-id OBJECT IDENTIFIER, value [0] EXPLICIT ANY }
  private def otherNameFrom(der: Array[Byte]): Option[SubjectAlternativeName.OtherName] = {
    Try {
      val sequence = ASN1Primitive.fromByteArray(der) match {
        // the JDK hands over the whole GeneralName choice, that is `[0] IMPLICIT OtherName`, not a bare SEQUENCE
        case tagged: ASN1TaggedObject => ASN1Sequence.getInstance(tagged, false)
        case primitive                => ASN1Sequence.getInstance(primitive)
      }
      val oid = ASN1ObjectIdentifier.getInstance(sequence.getObjectAt(0)).getId
      stringValueOf(sequence.getObjectAt(1)).map(value => SubjectAlternativeName.OtherName(Oid(oid), value))
    }.toOption.flatten
  }

  // the value is `[0] EXPLICIT ANY`, and re-encoding on the way out of the JDK can leave it wrapped more than once
  @tailrec
  private def stringValueOf(encodable: ASN1Encodable, depth: Int = 0): Option[String] = {
    if (depth > maxOtherNameTagDepth) None
    else
      encodable.toASN1Primitive match {
        case string: ASN1String       => Some(string.getString)
        case tagged: ASN1TaggedObject => stringValueOf(tagged.getObject, depth + 1)
        case _                        => None
      }
  }

  private val maxOtherNameTagDepth = 5

}

/** A distinguished name, kept both as its RFC 2253 rendering and as parsed RDNs.
  *
  * `value` exists for pattern-based extraction; every structural comparison has to go through
  * [[DistinguishedName.endsWith]] or [[DistinguishedName.hasTheSameRdnsAs]], never through `==`, which would also compare the rendering.
  */
final case class DistinguishedName(value: String, rdns: List[Rdn]) {

  def valuesOf(attributeName: String): List[String] = {
    val normalisedName = attributeName.toLowerCase
    rdns.flatMap(_.attributes.filter(_.name == normalisedName).map(_.value))
  }

  def hasTheSameRdnsAs(other: DistinguishedName): Boolean = rdns == other.rdns

  def endsWith(base: DistinguishedName): Boolean = rdns.endsWith(base.rdns)
}

object DistinguishedName {

  def from(principal: X500Principal): Either[String, DistinguishedName] = from(principal.getName(X500Principal.RFC2253))

  def from(value: String): Either[String, DistinguishedName] = {
    Try(new LdapName(value)).toEither.left
      .map(_ => s"Cannot parse '${value.show}' as a distinguished name")
      .map { ldapName =>
        // LdapName keeps RDNs least-significant-last; reversing puts them in the order they are written
        DistinguishedName(value, ldapName.getRdns.asScala.toList.reverse.map(rdnFrom))
      }
  }

  private def rdnFrom(rdn: javax.naming.ldap.Rdn) = {
    Rdn.of(
      rdn.toAttributes.getAll.asScala.toList
        .flatMap(attribute => attribute.getAll.asScala.toList.map(value => (attribute.getID, value.toString)))
    )
  }

}

/** A relative distinguished name. Holds more than one attribute only for multi-valued RDNs (`OU=a+OU=b`). */
final case class Rdn(attributes: List[Rdn.Attribute])

object Rdn {

  /** Attribute names are lower-cased - DN attribute names are case-insensitive per the DN specification. */
  final case class Attribute(name: String, value: String)

  def of(attributes: List[(String, String)]): Rdn = {
    Rdn(
      attributes
        .map { case (name, value) => Attribute(name.toLowerCase, value) }
        // the attributes of a multi-valued RDN are unordered, so sorting makes RDN equality order-insensitive
        .sortBy(attribute => (attribute.name, attribute.value))
    )
  }

}

final case class Oid(value: String)

sealed trait SubjectAlternativeName

object SubjectAlternativeName {
  final case class Dns(value: String) extends SubjectAlternativeName
  final case class Email(value: String) extends SubjectAlternativeName
  final case class Uri(value: String) extends SubjectAlternativeName
  final case class Ip(value: String) extends SubjectAlternativeName
  final case class OtherName(oid: Oid, value: String) extends SubjectAlternativeName

  /** How Active Directory stores the userPrincipalName. */
  val userPrincipalNameOid: Oid = Oid("1.3.6.1.4.1.311.20.2.3")
}
