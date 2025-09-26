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
package tech.beshu.ror.accesscontrol.audit.configurable

import cats.parse.{Parser0, Parser as P}
import cats.syntax.list.*
import tech.beshu.ror.audit.utils.AuditSerializationHelper.AuditFieldValueDescriptor

object AuditFieldValueDescriptorParser {

  private val lbrace = P.char('{')
  private val rbrace = P.char('}')
  private val key = P.charsWhile(c => c != '{' && c != '}')

  private val placeholder: P[Either[String, AuditFieldValueDescriptor]] =
    (lbrace *> key <* rbrace).map(k => deserializerAuditFieldValueDescriptor(k.trim.toUpperCase).toRight(k))

  private val text: P[AuditFieldValueDescriptor] =
    P.charsWhile(_ != '{').map(AuditFieldValueDescriptor.StaticText.apply)

  private val segment: P[Either[String, AuditFieldValueDescriptor]] =
    placeholder.orElse(text.map(Right(_)))

  private val parser: Parser0[List[Either[String, AuditFieldValueDescriptor]]] =
    segment.rep0 <* P.end

  def parse(s: String): Either[String, AuditFieldValueDescriptor] =
    parser.parseAll(s) match {
      case Left(err) =>
        Left(err.toString)
      case Right(segments) =>
        val (missing, ok) = segments.partitionMap(identity)
        missing.toNel match {
          case Some(missing) => Left(s"There are invalid placeholder values: ${missing.toList.distinct.mkString(", ")}")
          case None => ok match {
            case Nil => Right(AuditFieldValueDescriptor.StaticText(""))
            case single :: Nil => Right(single)
            case many => Right(AuditFieldValueDescriptor.Combined(many))
          }
        }
    }

  private def deserializerAuditFieldValueDescriptor(str: String): Option[AuditFieldValueDescriptor] = {
    str.toUpperCase match {
      case "IS_MATCHED" => Some(AuditFieldValueDescriptor.IsMatched)
      case "FINAL_STATE" => Some(AuditFieldValueDescriptor.FinalState)
      case "REASON" => Some(AuditFieldValueDescriptor.Reason)
      case "USER" => Some(AuditFieldValueDescriptor.User)
      case "IMPERSONATED_BY_USER" => Some(AuditFieldValueDescriptor.ImpersonatedByUser)
      case "ACTION" => Some(AuditFieldValueDescriptor.Action)
      case "INVOLVED_INDICES" => Some(AuditFieldValueDescriptor.InvolvedIndices)
      case "ACL_HISTORY" => Some(AuditFieldValueDescriptor.AclHistory)
      case "PROCESSING_DURATION_MILLIS" => Some(AuditFieldValueDescriptor.ProcessingDurationMillis)
      case "TIMESTAMP" => Some(AuditFieldValueDescriptor.Timestamp)
      case "ID" => Some(AuditFieldValueDescriptor.Id)
      case "CORRELATION_ID" => Some(AuditFieldValueDescriptor.CorrelationId)
      case "TASK_ID" => Some(AuditFieldValueDescriptor.TaskId)
      case "ERROR_TYPE" => Some(AuditFieldValueDescriptor.ErrorType)
      case "ERROR_MESSAGE" => Some(AuditFieldValueDescriptor.ErrorMessage)
      case "TYPE" => Some(AuditFieldValueDescriptor.Type)
      case "HTTP_METHOD" => Some(AuditFieldValueDescriptor.HttpMethod)
      case "HTTP_HEADER_NAMES" => Some(AuditFieldValueDescriptor.HttpHeaderNames)
      case "HTTP_PATH" => Some(AuditFieldValueDescriptor.HttpPath)
      case "X_FORWARDED_FOR_HTTP_HEADER" => Some(AuditFieldValueDescriptor.XForwardedForHttpHeader)
      case "REMOTE_ADDRESS" => Some(AuditFieldValueDescriptor.RemoteAddress)
      case "LOCAL_ADDRESS" => Some(AuditFieldValueDescriptor.LocalAddress)
      case "CONTENT" => Some(AuditFieldValueDescriptor.Content)
      case "CONTENT_LENGTH_IN_BYTES" => Some(AuditFieldValueDescriptor.ContentLengthInBytes)
      case "CONTENT_LENGTH_IN_KB" => Some(AuditFieldValueDescriptor.ContentLengthInKb)
      case "ES_NODE_NAME" => Some(AuditFieldValueDescriptor.EsNodeName)
      case "ES_CLUSTER_NAME" => Some(AuditFieldValueDescriptor.EsClusterName)
      case _ => None
    }

  }

}
