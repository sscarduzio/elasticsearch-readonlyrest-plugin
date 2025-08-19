

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

import tech.beshu.ror.audit.AuditFieldValue

object AuditFieldValueDeserializer {

  private val pattern = "\\{([^}]+)\\}".r

  def deserialize(str: String): Either[String, AuditFieldValue] = {
    val matches = pattern.findAllMatchIn(str).toList

    val (parts, missing, lastIndex) =
      matches.foldLeft((List.empty[AuditFieldValue], List.empty[String], 0)) {
        case ((partsAcc, missingAcc, lastEnd), m) =>
          val key = m.group(1)
          val before = str.substring(lastEnd, m.start)
          val partBefore = if (before.nonEmpty) List(AuditFieldValue.StaticText(before)) else Nil

          val (partAfter, newMissing) = deserializerAuditFieldValue(key) match {
            case Some(placeholder) => (List(placeholder), Nil)
            case None => (Nil, List(key))
          }

          (partsAcc ++ partBefore ++ partAfter, missingAcc ++ newMissing, m.end)
      }

    val trailing = if (lastIndex < str.length) List(AuditFieldValue.StaticText(str.substring(lastIndex))) else Nil
    val allParts = parts ++ trailing

    missing match {
      case Nil => allParts match {
        case Nil => Right(AuditFieldValue.StaticText(""))
        case singleElement :: Nil => Right(singleElement)
        case multipleElements => Right(AuditFieldValue.Combined(multipleElements))
      }
      case missingList => Left(s"There are invalid placeholder values: ${missingList.mkString(", ")}")
    }
  }

  private def deserializerAuditFieldValue(str: String): Option[AuditFieldValue] = {
    str match {
      case "IS_MATCHED" => Some(AuditFieldValue.IsMatched)
      case "FINAL_STATE" => Some(AuditFieldValue.FinalState)
      case "REASON" => Some(AuditFieldValue.Reason)
      case "USER" => Some(AuditFieldValue.User)
      case "IMPERSONATED_BY_USER" => Some(AuditFieldValue.ImpersonatedByUser)
      case "ACTION" => Some(AuditFieldValue.Action)
      case "INVOLVED_INDICES" => Some(AuditFieldValue.InvolvedIndices)
      case "ACL_HISTORY" => Some(AuditFieldValue.AclHistory)
      case "PROCESSING_DURATION_MILLIS" => Some(AuditFieldValue.ProcessingDurationMillis)
      case "TIMESTAMP" => Some(AuditFieldValue.Timestamp)
      case "ID" => Some(AuditFieldValue.Id)
      case "CORRELATION_ID" => Some(AuditFieldValue.CorrelationId)
      case "TASK_ID" => Some(AuditFieldValue.TaskId)
      case "ERROR_TYPE" => Some(AuditFieldValue.ErrorType)
      case "ERROR_MESSAGE" => Some(AuditFieldValue.ErrorMessage)
      case "TYPE" => Some(AuditFieldValue.Type)
      case "HTTP_METHOD" => Some(AuditFieldValue.HttpMethod)
      case "HTTP_HEADER_NAMES" => Some(AuditFieldValue.HttpHeaderNames)
      case "HTTP_PATH" => Some(AuditFieldValue.HttpPath)
      case "X_FORWARDED_FOR_HTTP_HEADER" => Some(AuditFieldValue.XForwardedForHttpHeader)
      case "REMOTE_ADDRESS" => Some(AuditFieldValue.RemoteAddress)
      case "LOCAL_ADDRESS" => Some(AuditFieldValue.LocalAddress)
      case "CONTENT" => Some(AuditFieldValue.Content)
      case "CONTENT_LENGTH_IN_BYTES" => Some(AuditFieldValue.ContentLengthInBytes)
      case "CONTENT_LENGTH_IN_KB" => Some(AuditFieldValue.ContentLengthInKb)
      case "ES_NODE_NAME" => Some(AuditFieldValue.EsNodeName)
      case "ES_CLUSTER_NAME" => Some(AuditFieldValue.EsClusterName)
      case _ => None
    }

  }

}
