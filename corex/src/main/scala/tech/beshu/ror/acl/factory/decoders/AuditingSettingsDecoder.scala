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
package tech.beshu.ror.acl.factory.decoders

import java.time.ZoneId
import java.time.format.DateTimeFormatter

import io.circe.Decoder
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.Constants
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.AuditingSettingsCreationError
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.logging.AuditingTool
import tech.beshu.ror.audit.AuditLogSerializer
import tech.beshu.ror.audit.adapters.DeprecatedAuditLogSerializerAdapter
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializer
import tech.beshu.ror.acl.utils.CirceOps._

import scala.util.{Failure, Success, Try}

object AuditingSettingsDecoder extends Logging {

  lazy val instance: Decoder[Option[AuditingTool.Settings]] =
    Decoder.instance { c =>
      for {
        auditCollectorEnabled <- c.downField("audit_collector").as[Option[Boolean]]
        settings <-
          if (auditCollectorEnabled.getOrElse(false)) {
            for {
              indexNameFormatter <- c.downField("audit_index_template").as[Option[DateTimeFormatter]]
              customAuditSerializer <- c.downField("audit_serializer").as[Option[AuditLogSerializer]]
            } yield Some(AuditingTool.Settings(
              indexNameFormatter.getOrElse(DateTimeFormatter.ofPattern(Constants.AUDIT_LOG_DEFAULT_INDEX_TEMPLATE).withZone(ZoneId.of("UTC"))),
              customAuditSerializer.getOrElse(new DefaultAuditLogSerializer)
            ))
          } else {
            Decoder.const(Option.empty[AuditingTool.Settings]).tryDecode(c)
          }
      } yield settings
    }


  private implicit val indexNameFormatterDecoder: Decoder[DateTimeFormatter] =
    Decoder
      .decodeString
      .emapE { patternStr =>
        Try(DateTimeFormatter.ofPattern(patternStr).withZone(ZoneId.of("UTC"))) match {
          case Success(formatter) => Right(formatter)
          case Failure(ex) => Left(AuditingSettingsCreationError(Message(
            s"Illegal pattern specified for audit_index_template. Have you misplaced quotes? Search for 'DateTimeFormatter patterns' to learn the syntax. Pattern was: $patternStr error: ${ex.getMessage}"
          )))
        }
      }

  private implicit val customAuditLogSerializer: Decoder[AuditLogSerializer] =
    Decoder
      .decodeString
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

}
