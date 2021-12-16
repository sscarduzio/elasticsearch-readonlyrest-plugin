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
package tech.beshu.ror.accesscontrol.factory.decoders

import com.softwaremill.sttp.Uri
import io.circe.{Decoder, HCursor}
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain.RorAuditIndexTemplate.CreationError
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, RorAuditIndexTemplate}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.AuditingSettingsCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.common.uriDecoder
import tech.beshu.ror.accesscontrol.logging.AuditingTool
import tech.beshu.ror.accesscontrol.utils.SyncDecoderCreator
import tech.beshu.ror.audit.AuditLogSerializer
import tech.beshu.ror.audit.adapters.DeprecatedAuditLogSerializerAdapter
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializer

import scala.util.{Failure, Success, Try}

object AuditingSettingsDecoder extends Logging {

  lazy val instance: Decoder[Option[AuditingTool.Settings]] =
    Decoder.instance { c =>
      whenEnabled(c) {
        for {
          auditIndexTemplate <- decodeOptionalSetting[RorAuditIndexTemplate](c)("index_template", fallbackKey = "audit_index_template")
          customAuditSerializer <- decodeOptionalSetting[AuditLogSerializer](c)("serializer", fallbackKey = "audit_serializer")
          remoteAuditCluster <- decodeOptionalSetting[AuditCluster.RemoteAuditCluster](c)("cluster", fallbackKey = "audit_cluster")
        } yield AuditingTool.Settings(
          auditIndexTemplate.getOrElse(RorAuditIndexTemplate.default),
          customAuditSerializer.getOrElse(new DefaultAuditLogSerializer),
          remoteAuditCluster.getOrElse(AuditCluster.LocalAuditCluster)
        )
      }
    }

  private implicit val rorAuditIndexTemplateDecoder: Decoder[RorAuditIndexTemplate] =
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .emapE { patternStr =>
        RorAuditIndexTemplate
          .from(patternStr)
          .left
          .map {
            case CreationError.ParsingError(msg) =>
              AuditingSettingsCreationError(Message(
                s"Illegal pattern specified for audit_index_template. Have you misplaced quotes? Search for 'DateTimeFormatter patterns' to learn the syntax. Pattern was: $patternStr error: $msg"
              ))
          }
      }
      .decoder

  private implicit val customAuditLogSerializer: Decoder[AuditLogSerializer] =
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .emapE { fullClassName =>
        Try {
          Class.forName(fullClassName).getDeclaredConstructor().newInstance() match {
            case serializer: tech.beshu.ror.audit.AuditLogSerializer =>
              Some(serializer)
            case serializer: tech.beshu.ror.requestcontext.AuditLogSerializer[_] =>
              Some(new DeprecatedAuditLogSerializerAdapter(serializer))
            case _ => None
          }
        } match {
          case Success(Some(customSerializer)) =>
            logger.info(s"Using custom serializer: ${customSerializer.getClass.getName}")
            Right(customSerializer)
          case Success(None) => Left(AuditingSettingsCreationError(Message(s"Class $fullClassName is not a subclass of ${classOf[AuditLogSerializer].getName} or ${classOf[tech.beshu.ror.requestcontext.AuditLogSerializer[_]].getName}")))
          case Failure(ex) => Left(AuditingSettingsCreationError(Message(s"Cannot create instance of class '$fullClassName', error: ${ex.getMessage}")))
        }
      }
      .decoder

  private implicit val remoteAuditClusterDecoder: Decoder[AuditCluster.RemoteAuditCluster] =
    SyncDecoderCreator
      .from(Decoder.decodeNonEmptyList[Uri])
      .withError(AuditingSettingsCreationError(Message("Non empty list of valid URI is required")))
      .map(AuditCluster.RemoteAuditCluster)
      .decoder

  private def whenEnabled(cursor: HCursor)(decoding: => Decoder.Result[AuditingTool.Settings]) = {
    for {
      isEnabled <- decodeOptionalSetting[Boolean](cursor)("collector", fallbackKey = "audit_collector")
      result <- if (isEnabled.getOrElse(false)) decoding.map(Some.apply) else Right(None)
    } yield result
  }

  private def decodeOptionalSetting[T: Decoder](cursor: HCursor)(key: String, fallbackKey: String): Decoder.Result[Option[T]] = {
    cursor.downField("audit").get[Option[T]](key)
      .flatMap {
        case Some(value) => Right(Some(value))
        case None => cursor.get[Option[T]](fallbackKey)
      }
  }
}
