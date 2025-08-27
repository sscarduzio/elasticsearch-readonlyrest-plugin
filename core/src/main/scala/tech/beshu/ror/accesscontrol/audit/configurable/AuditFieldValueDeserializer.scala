

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

import tech.beshu.ror.audit.AuditSerializationHelper.AuditFieldValueDescriptor

object AuditFieldValueDescriptorDeserializer {

  private val pattern = "\\{([^}]+)\\}".r

  def deserialize(str: String): Either[String, AuditFieldValueDescriptor] = {
    val matches = pattern.findAllMatchIn(str).toList

    val (parts, missing, lastIndex) =
      matches.foldLeft((List.empty[AuditFieldValueDescriptor], List.empty[String], 0)) {
        case ((partsAcc, missingAcc, lastEnd), m) =>
          val key = m.group(1)
          val before = str.substring(lastEnd, m.start)
          val partBefore = if (before.nonEmpty) List(AuditFieldValueDescriptor.StaticText(before)) else Nil

          val (partAfter, newMissing) = deserializerAuditFieldValueDescriptor(key) match {
            case Some(placeholder) => (List(placeholder), Nil)
            case None => (Nil, List(key))
          }

          (partsAcc ++ partBefore ++ partAfter, missingAcc ++ newMissing, m.end)
      }

    val trailing = if (lastIndex < str.length) List(AuditFieldValueDescriptor.StaticText(str.substring(lastIndex))) else Nil
    val allParts = parts ++ trailing

    missing match {
      case Nil => allParts match {
        case Nil => Right(AuditFieldValueDescriptor.StaticText(""))
        case singleElement :: Nil => Right(singleElement)
        case multipleElements => Right(AuditFieldValueDescriptor.Combined(multipleElements))
      }
      case missingList => Left(s"There are invalid placeholder values: ${missingList.mkString(", ")}")
    }
  }

  private def deserializerAuditFieldValueDescriptor(str: String): Option[AuditFieldValueDescriptor] = {
    str match {
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
