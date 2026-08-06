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
package tech.beshu.ror.accesscontrol.factory.decoders.definitions

import cats.Id
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{ACursor, Decoder}
import tech.beshu.ror.accesscontrol.blocks.definitions.PkiDef
import tech.beshu.ror.accesscontrol.blocks.definitions.PkiDef.{ExtractionSpec, SanType, Scope}
import tech.beshu.ror.accesscontrol.domain.{DistinguishedName, JavaRegex}
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.common.*
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecodingFailureUtils.decodingFailureFrom
import tech.beshu.ror.accesscontrol.utils.{ADecoder, SyncDecoder, SyncDecoderCreator}
import tech.beshu.ror.implicits.*

object PkiDefinitionsDecoder {

  private val definitionsSectionName = "pkis"

  given nameDecoder: Decoder[PkiDef.Name] = DecoderHelpers.decodeNonEmptyStringField.map(PkiDef.Name.apply)

  lazy val instance: ADecoder[Id, Definitions[PkiDef]] = {
    DefinitionsBaseDecoder.instance[Id, PkiDef](definitionsSectionName)
  }

  private given pkiDefDecoder: SyncDecoder[PkiDef] = {
    SyncDecoderCreator
      .instance { c =>
        for {
          name <- c.downFieldAs[PkiDef.Name]("name")
          scope <- scopeDecoder(c)
          usersExtraction <- usersExtractionDecoder(name)(c)
          groupsExtraction <- groupsExtractionDecoder(name)(c)
        } yield PkiDef(name, scope, usersExtraction, groupsExtraction)
      }
      .mapError(DefinitionsLevelCreationError.apply)
  }

  private val scopeDecoder: Decoder[Scope] =
    Decoder.instance { c =>
      for {
        subjectDnBase <- c.downFieldAs[Option[DistinguishedName]]("subject_dn_base")
        issuerDn <- c.downFieldAs[Option[DistinguishedName]]("issuer_dn")
      } yield Scope(subjectDnBase, issuerDn)
    }

  private given distinguishedNameDecoder: Decoder[DistinguishedName] =
    Decoder.decodeString.toSyncDecoder
      .emapE[DistinguishedName] { value =>
        DistinguishedName.from(value).left.map(error => DefinitionsLevelCreationError(Message(error)))
      }
      .decoder

  private def usersExtractionDecoder(pkiName: PkiDef.Name): Decoder[ExtractionSpec] =
    Decoder.instance { c =>
      extractionSpecFrom(
        cursor = c.downField("users"),
        pkiName = pkiName,
        target = ExtractionTarget.Users
      )
    }

  private def groupsExtractionDecoder(pkiName: PkiDef.Name): Decoder[Option[ExtractionSpec]] =
    Decoder.instance { c =>
      c.downField("groups").success match {
        case None               => Right(None)
        case Some(groupsCursor) =>
          extractionSpecFrom(groupsCursor, pkiName, ExtractionTarget.Groups).map(Some.apply)
      }
    }

  private def extractionSpecFrom(cursor: ACursor, pkiName: PkiDef.Name, target: ExtractionTarget) = {
    for {
      _ <- rejectUnsupportedKeys(cursor, pkiName, target)
      mode <- cursor.downFieldAs[Option[ExtractionMode]]("mode")
      spec <- mode.getOrElse(ExtractionMode.SubjectDnAttribute) match {
        case ExtractionMode.SubjectDnAttribute => subjectDnAttributeSpecFrom(cursor, pkiName, target)
        case ExtractionMode.SubjectDnPattern   => subjectDnPatternSpecFrom(cursor, pkiName, target)
        case ExtractionMode.San                => sanSpecFrom(cursor, pkiName, target)
      }
    } yield spec
  }

  // a certificate carries one string per attribute, so there is no second attribute to read a display name
  // from; saying so is more use than silently ignoring the key
  private def rejectUnsupportedKeys(cursor: ACursor, pkiName: PkiDef.Name, target: ExtractionTarget) = {
    if (target == ExtractionTarget.Groups && cursor.downField("group_name_attribute").succeeded) {
      Left(
        error(
          s"Group names (group_name_attribute) are not supported by PKI providers, because a certificate carries only one value per attribute [pki ${pkiName.value.show}]"
        )
      )
    } else {
      Right(())
    }
  }

  private def subjectDnAttributeSpecFrom(cursor: ACursor, pkiName: PkiDef.Name, target: ExtractionTarget) = {
    cursor.downFieldAs[Option[NonEmptyString]](target.attributeKey).flatMap {
      case Some(attributeName) => Right(ExtractionSpec.SubjectDnAttribute(attributeName))
      case None                =>
        target.defaultAttribute
          .map(attributeName => Right(ExtractionSpec.SubjectDnAttribute(attributeName)))
          .getOrElse(
            Left(
              error(
                s"'${target.attributeKey.show}' is required by the '${ExtractionMode.SubjectDnAttribute.configValue.show}' mode [pki ${pkiName.value.show}]"
              )
            )
          )
    }
  }

  private def subjectDnPatternSpecFrom(cursor: ACursor, pkiName: PkiDef.Name, target: ExtractionTarget) = {
    optionalPatternFrom(cursor, pkiName, target).flatMap {
      case Some(pattern) => Right(ExtractionSpec.SubjectDnPattern(pattern))
      case None          => Left(error(requiredKeyMessage("pattern", ExtractionMode.SubjectDnPattern, pkiName)))
    }
  }

  private def sanSpecFrom(cursor: ACursor, pkiName: PkiDef.Name, target: ExtractionTarget) = {
    for {
      sanType <- cursor.downFieldAs[Option[SanType]]("san_type").flatMap {
        _.toRight(error(requiredKeyMessage("san_type", ExtractionMode.San, pkiName)))
      }
      pattern <- optionalPatternFrom(cursor, pkiName, target)
    } yield ExtractionSpec.San(sanType, pattern)
  }

  private def optionalPatternFrom(cursor: ACursor, pkiName: PkiDef.Name, target: ExtractionTarget) = {
    cursor.downFieldAs[Option[NonEmptyString]]("pattern").flatMap {
      case None          => Right(None)
      case Some(pattern) => validPatternFrom(pattern, pkiName, target).map(Some.apply)
    }
  }

  private def requiredKeyMessage(key: String, mode: ExtractionMode, pkiName: PkiDef.Name) =
    s"'${key.show}' is required by the '${mode.configValue.show}' mode [pki ${pkiName.value.show}]"

  private def validPatternFrom(pattern: NonEmptyString, pkiName: PkiDef.Name, target: ExtractionTarget) = {
    JavaRegex
      .compile(pattern.value)
      .toEither
      .left
      .map(_ => error(s"Cannot compile '${pattern.value.show}' as a regular expression [pki ${pkiName.value.show}]"))
      .flatMap { regex =>
        val matcher = regex.pattern.matcher("")
        if (matcher.groupCount() != 1) {
          Left(
            error(
              s"The '${target.sectionName.show}' pattern of the '${pkiName.value.show}' PKI has to define exactly one capture group, but defines ${matcher.groupCount().show}"
            )
          )
        } else if (matcher.find()) {
          Left(
            error(
              s"The '${target.sectionName.show}' pattern of the '${pkiName.value.show}' PKI matches an empty value, so it cannot identify anything"
            )
          )
        } else {
          Right(regex)
        }
      }
  }

  private def error(message: String) = decodingFailureFrom(DefinitionsLevelCreationError(Message(message)))

  private sealed trait ExtractionTarget {
    def sectionName: String
    def attributeKey: String
    def defaultAttribute: Option[NonEmptyString]
  }

  private object ExtractionTarget {

    case object Users extends ExtractionTarget {
      override val sectionName: String = "users"
      override val attributeKey: String = "user_id_attribute"

      override val defaultAttribute: Option[NonEmptyString] = Some(
        ExtractionSpec.SubjectDnAttribute.defaultUserIdAttribute
      )

    }

    case object Groups extends ExtractionTarget {
      override val sectionName: String = "groups"
      override val attributeKey: String = "group_id_attribute"
      override val defaultAttribute: Option[NonEmptyString] = None
    }

  }

  private sealed abstract class ExtractionMode(val configValue: String)

  private object ExtractionMode {

    case object SubjectDnAttribute extends ExtractionMode("subject_dn_attribute")
    case object SubjectDnPattern extends ExtractionMode("subject_dn_pattern")
    case object San extends ExtractionMode("san")

    private val all = List(SubjectDnAttribute, SubjectDnPattern, San)

    given Decoder[ExtractionMode] =
      Decoder.decodeString.toSyncDecoder
        .emapE[ExtractionMode] { value =>
          all
            .find(_.configValue == value)
            .toRight(
              DefinitionsLevelCreationError(
                Message(
                  s"Unknown mode of PKI identity extraction: ${value.show}. Supported modes are ${all.map(_.configValue).mkString(", ").show}"
                )
              )
            )
        }
        .decoder

  }

  private given sanTypeDecoder: Decoder[SanType] =
    Decoder.decodeString.toSyncDecoder
      .emapE[SanType] { value =>
        SanType
          .fromConfigValue(value)
          .toRight(
            DefinitionsLevelCreationError(
              Message(
                s"Unknown SAN type: ${value.show}. Supported types are ${SanType.values.map(_.configValue).mkString(", ").show}"
              )
            )
          )
      }
      .decoder

}
