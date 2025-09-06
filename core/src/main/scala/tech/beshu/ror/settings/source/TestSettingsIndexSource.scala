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
package tech.beshu.ror.settings.source

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{Codec, Decoder, Encoder}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ExternalAuthorizationService}
import tech.beshu.ror.accesscontrol.blocks.mocks.AuthServicesMocks
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.ExternalAuthenticationServiceMock.ExternalAuthenticationUserMock
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.ExternalAuthorizationServiceMock.ExternalAuthorizationServiceUserMock
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.LdapServiceMock.LdapUserMock
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.{ExternalAuthenticationServiceMock, ExternalAuthorizationServiceMock, LdapServiceMock}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.{Group, GroupName, RorSettingsIndex, User}
import tech.beshu.ror.configuration.TestRorSettings.Expiration
import tech.beshu.ror.configuration.{RawRorSettings, RawRorSettingsYamlParser, TestRorSettings}
import tech.beshu.ror.es.IndexDocumentReader
import tech.beshu.ror.settings.source.TestSettingsIndexSource.Const
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.DurationOps.*
import tech.beshu.ror.utils.json.KeyCodec

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import scala.concurrent.duration.Duration
import scala.util.Try

class TestSettingsIndexSource private(indexJsonContentService: IndexDocumentReader,
                                      settingsIndex: RorSettingsIndex)
                                     (implicit codec: Codec[TestRorSettings])
  extends IndexSettingsSource[TestRorSettings](indexJsonContentService, settingsIndex.index, documentId = Const.id)

object TestSettingsIndexSource {

  def create(indexJsonContentService: IndexDocumentReader,
             settingsIndex: RorSettingsIndex,
             settingsYamlParser: RawRorSettingsYamlParser): TestSettingsIndexSource = {
    implicit val codec: Codec[TestRorSettings] = createTestRorSettingsCodec(settingsYamlParser)
    new TestSettingsIndexSource(indexJsonContentService, settingsIndex)
  }

  private object Const {
    val id = "2"
    object properties {
      val settings = "settings"
      val expirationTtl = "expiration_ttl_millis"
      val expirationTime = "expiration_timestamp"
      val mocks = "auth_services_mocks"
    }
  }

  private def createTestRorSettingsCodec(yamlParser: RawRorSettingsYamlParser): Codec[TestRorSettings] =
    Codec.from(decoder(yamlParser), encoder(yamlParser))

  import Const.*

  private def encoder(implicit yamlParser: RawRorSettingsYamlParser): Encoder[TestRorSettings] =
    Encoder.forProduct4(properties.settings, properties.mocks, properties.expirationTtl, properties.expirationTime){
      testRorSettings => (
        testRorSettings.rawSettings,
        testRorSettings.mocks,
        testRorSettings.expiration.ttl,
        testRorSettings.expiration.validTo
      )
    }

  private def decoder(implicit yamlParser: RawRorSettingsYamlParser): Decoder[TestRorSettings] = Decoder.instance { c =>
    for {
      settings <- c.downField(properties.settings).as[RawRorSettings]
      mocks <- c.downField(properties.mocks).as[AuthServicesMocks]
      expirationTtl <- c.downField(properties.expirationTtl).as[PositiveFiniteDuration]
      expirationTime <- c.downField(properties.expirationTime).as[Instant]
    } yield TestRorSettings(settings, mocks, Expiration(expirationTtl, expirationTime))
  }

  private implicit def settingsCodec(implicit yamlParser: RawRorSettingsYamlParser): Codec[RawRorSettings] =
    new RawRorSettingsCodec(yamlParser)

  private implicit val mocksCodec: Codec[AuthServicesMocks] = {
    implicit val nonEmptyStringCodec: Codec[NonEmptyString] =
      Codec.from(Decoder.decodeString.emap(NonEmptyString.from), Encoder.encodeString.contramap(_.value))
    implicit val userIdCodec: Codec[User.Id] =
      Codec.from(nonEmptyStringCodec.map(User.Id.apply), nonEmptyStringCodec.contramap(_.value))
    implicit val groupIdCodec: Codec[GroupId] =
      Codec.from(
        nonEmptyStringCodec.map(GroupId.apply),
        nonEmptyStringCodec.contramap(_.value)
      )

    implicit val groupCodec: Codec[Group] = {
      implicit val groupNameCodec: Codec[GroupName] = Codec.from(
        nonEmptyStringCodec.map(GroupName.apply),
        nonEmptyStringCodec.contramap(_.value)
      )
      Codec.forProduct2("id", "name")(Group.apply)(group => (group.id, group.name))
    }
    implicit val ldapServiceMock: Codec[LdapServiceMock] = {
      implicit val userMock: Codec[LdapUserMock] = {
        // "groups" left for backward compatibility
        val encoder: Encoder[LdapUserMock] = Encoder.forProduct3("id", "groups", "userGroups")(
          userMock => (userMock.id, userMock.groups.map(_.id), userMock.groups)
        )
        val deprecatedFormatDecoder = Decoder.forProduct2("id", "groups")((id: User.Id, groupIds: List[GroupId]) =>
          LdapUserMock(id, groupIds.map(Group.from).toCovariantSet)
        )
        val decoder: Decoder[LdapUserMock] = Decoder.forProduct2("id", "userGroups")(LdapUserMock.apply)
        Codec.from(decoder.or(deprecatedFormatDecoder), encoder)
      }
      Codec.forProduct1("users")(LdapServiceMock.apply)(_.users)
    }

    implicit val extAuthenticationMock: Codec[ExternalAuthenticationServiceMock] = {
      implicit val userMock: Codec[ExternalAuthenticationUserMock] =
        Codec.forProduct1("id")(ExternalAuthenticationUserMock.apply)(_.id)
      Codec.forProduct1("users")(ExternalAuthenticationServiceMock.apply)(_.users)
    }

    implicit val extAuthorizationMock: Codec[ExternalAuthorizationServiceMock] = {
      implicit val userMock: Codec[ExternalAuthorizationServiceUserMock] = {
        // "groups" left for backward compatibility
        val encoder = Encoder.forProduct3("id", "groups", "userGroups")(
          (userMock: ExternalAuthorizationServiceUserMock) => (userMock.id, userMock.groups.map(_.id), userMock.groups)
        )
        val deprecatedFormatDecoder = Decoder.forProduct2("id", "groups")((id: User.Id, groupIds: List[GroupId]) =>
          ExternalAuthorizationServiceUserMock(id, groupIds.map(Group.from).toCovariantSet)
        )
        val decoder = Decoder.forProduct2("id", "userGroups")(ExternalAuthorizationServiceUserMock.apply)
        Codec.from(decoder.or(deprecatedFormatDecoder), encoder)
      }
      Codec.forProduct1("users")(ExternalAuthorizationServiceMock.apply)(_.users)
    }

    implicit val ldapKeyCodec: KeyCodec[LdapService.Name] = KeyCodec.from[LdapService.Name](
      NonEmptyString.unapply(_).map(LdapService.Name.apply),
      _.value.value
    )

    implicit val externalAuthenticationKeyCodec: KeyCodec[ExternalAuthenticationService.Name] =
      KeyCodec.from[ExternalAuthenticationService.Name](
        NonEmptyString.unapply(_).map(ExternalAuthenticationService.Name.apply),
        _.value.value
      )

    implicit val externalAuthorizationKeyCodec: KeyCodec[ExternalAuthorizationService.Name] =
      KeyCodec.from[ExternalAuthorizationService.Name](
        NonEmptyString.unapply(_).map(ExternalAuthorizationService.Name.apply),
        _.value.value
      )

    Codec.forProduct3(
      "ldapMocks",
      "externalAuthenticationMocks",
      "externalAuthorizationMocks"
    )(AuthServicesMocks.apply)(e => (e.ldapMocks, e.externalAuthenticationServiceMocks, e.externalAuthorizationServiceMocks))
  }

  private implicit lazy val expirationTtlDecoder: Codec[PositiveFiniteDuration] = {
    val decoder = Decoder.decodeString.emap { str =>
      for {
        duration <- Try(Duration(str.toLong, "ms")).toEither.left.map(_ => s"Cannot create decode string '$str' to duration")
        positiveFiniteDuration <- duration.toRefinedPositive
      } yield positiveFiniteDuration
    }
    val encoder: Encoder[PositiveFiniteDuration] = Encoder.encodeString.contramap(_.value.toMillis.toString)
    Codec.from(decoder, encoder)
  }

  private implicit lazy val expirationTimeDecoder: Decoder[Instant] = {
    val decoder = Decoder.decodeString.emap { str =>
      Try(DateTimeFormatter.ISO_DATE_TIME.parse(str))
        .map(Instant.from)
        .toEither
        .left.map(_ => s"Cannot decode string 'str' to date")
    }
    val encoder: Encoder[Instant] = Encoder.encodeString.contramap(_.atOffset(ZoneOffset.UTC).toString)
    Codec.from(decoder, encoder)
  }

}