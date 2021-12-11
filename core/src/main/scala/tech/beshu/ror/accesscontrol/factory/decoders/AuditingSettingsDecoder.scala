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
import io.circe.Decoder
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
      for {
        auditCollectorEnabled <- c.downField("audit_collector").as[Option[Boolean]]
        settings <-
          if (auditCollectorEnabled.getOrElse(false)) {
            for {
              auditIndexTemplate <- c.downField("audit_index_template").as[Option[RorAuditIndexTemplate]]
              customAuditSerializer <- c.downField("audit_serializer").as[Option[AuditLogSerializer]]
              auditCluster <- c.downField("audit_cluster").as[Option[AuditCluster]]
            } yield Some(AuditingTool.Settings(
              auditIndexTemplate.getOrElse(RorAuditIndexTemplate.default),
              customAuditSerializer.getOrElse(new DefaultAuditLogSerializer),
              auditCluster
            ))
          } else {
            Decoder.const(Option.empty[AuditingTool.Settings]).tryDecode(c)
          }
      } yield settings
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

  private implicit val auditClusterDecoder: Decoder[AuditCluster] =
    SyncDecoderCreator
      .from(Decoder.decodeSet[Uri])
      .map(AuditCluster)
      .decoder
}
