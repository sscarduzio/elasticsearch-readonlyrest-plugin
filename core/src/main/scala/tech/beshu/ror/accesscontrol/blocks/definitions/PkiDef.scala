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
package tech.beshu.ror.accesscontrol.blocks.definitions

import cats.{Eq, Show}
import enumeratum.{Enum, EnumEntry}
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.definitions.PkiDef.{ExtractionSpec, Name, Scope}
import tech.beshu.ror.accesscontrol.domain.{ClientCertificate, DistinguishedName, JavaRegex, SubjectAlternativeName}
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions.Item

/** A named source of identity carried by TLS client certificates.
  *
  * Shaped like an `ldaps` entry minus its search half: a certificate arrives with the request, so there is
  * no directory to look anything up in. What remains is which certificates this provider will speak for
  * ([[Scope]]) and how to read an identity out of them.
  */
final case class PkiDef(
    override val id: PkiDef#Id,
    scope: Scope,
    usersExtraction: ExtractionSpec,
    groupsExtraction: Option[ExtractionSpec]
) extends Item {

  override type Id = Name
  override val idShow: Show[Name] = Show.show(_.value.value)
}

object PkiDef {

  final case class Name(value: NonEmptyString)

  implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals

  /** Restricts which certificates the provider will accept an identity from.
    *
    * Both are post-hoc checks on an already-verified certificate, so they behave identically no matter
    * which component terminated TLS.
    */
  final case class Scope(subjectDnBase: Option[DistinguishedName], issuerDn: Option[DistinguishedName]) {

    def accepts(certificate: ClientCertificate): Boolean = {
      subjectDnBase.forall(certificate.subjectDn.endsWith) &&
      issuerDn.forall(certificate.issuerDn.hasTheSameRdnsAs)
    }

  }

  object Scope {
    val unrestricted: Scope = Scope(subjectDnBase = None, issuerDn = None)
  }

  /** How to read values out of a certificate.
    *
    * Always yields every value it finds; taking the first one is what makes the difference between a
    * username (one value) and groups (all of them).
    */
  sealed trait ExtractionSpec {
    def extractFrom(certificate: ClientCertificate): List[String]
  }

  object ExtractionSpec {

    final case class SubjectDnAttribute(attributeName: NonEmptyString) extends ExtractionSpec {
      override def extractFrom(certificate: ClientCertificate): List[String] =
        certificate.subjectDn.valuesOf(attributeName.value)
    }

    object SubjectDnAttribute {
      val defaultUserIdAttribute: NonEmptyString = NonEmptyString.unsafeFrom("CN")
    }

    final case class SubjectDnPattern(pattern: JavaRegex) extends ExtractionSpec {
      override def extractFrom(certificate: ClientCertificate): List[String] =
        capturedValuesOf(pattern, certificate.subjectDn.value).map(unescapeDnValue)
    }

    final case class San(sanType: SanType, pattern: Option[JavaRegex]) extends ExtractionSpec {

      override def extractFrom(certificate: ClientCertificate): List[String] = {
        val values = valuesOf(sanType, certificate)
        pattern match {
          case Some(regex) => values.flatMap(value => capturedValuesOf(regex, value).headOption)
          case None        => values
        }
      }

    }

    private def valuesOf(sanType: SanType, certificate: ClientCertificate) = {
      certificate.subjectAlternativeNames.collect {
        case SubjectAlternativeName.Dns(value) if sanType == SanType.Dns     => value
        case SubjectAlternativeName.Email(value) if sanType == SanType.Email => value
        case SubjectAlternativeName.Uri(value) if sanType == SanType.Uri     => value
        case SubjectAlternativeName.Ip(value) if sanType == SanType.Ip       => value
        case SubjectAlternativeName.OtherName(oid, value)
            if sanType == SanType.Upn && oid == SubjectAlternativeName.userPrincipalNameOid =>
          value
      }
    }

    private def capturedValuesOf(regex: JavaRegex, value: String) = {
      val matcher = regex.pattern.matcher(value)
      Iterator.continually(matcher).takeWhile(_.find()).map(_.group(1)).toList
    }

    // patterns are written against the RFC 2253 rendering of the DN, where separators appearing inside a
    // value are escaped; a captured value has to be handed on the way the DN parser would have produced it
    private def unescapeDnValue(value: String): String = {
      val builder = new StringBuilder(value.length)
      var index = 0
      while (index < value.length) {
        value.charAt(index) match {
          case '\\' if index + 2 < value.length && isHexPairAt(value, index + 1) =>
            builder.append(Integer.parseInt(value.substring(index + 1, index + 3), 16).toChar)
            index += 3
          case '\\' if index + 1 < value.length =>
            builder.append(value.charAt(index + 1))
            index += 2
          case character =>
            builder.append(character)
            index += 1
        }
      }
      builder.toString()
    }

    private def isHexPairAt(value: String, index: Int) =
      isHexDigit(value.charAt(index)) && isHexDigit(value.charAt(index + 1))

    private def isHexDigit(character: Char) =
      character.isDigit || ('a' to 'f').contains(character.toLower)
  }

  sealed abstract class SanType(val configValue: String) extends EnumEntry

  object SanType extends Enum[SanType] {

    case object Dns extends SanType("dns")
    case object Email extends SanType("email")
    case object Uri extends SanType("uri")
    case object Ip extends SanType("ip")

    /** The `otherName` entry Active Directory stores the userPrincipalName in. */
    case object Upn extends SanType("upn")

    override val values: IndexedSeq[SanType] = findValues

    def fromConfigValue(str: String): Option[SanType] = values.find(_.configValue == str.toLowerCase)
  }

}
