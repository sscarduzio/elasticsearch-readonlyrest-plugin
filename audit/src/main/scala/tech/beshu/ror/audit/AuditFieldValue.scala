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
package tech.beshu.ror.audit

private[ror] sealed trait AuditFieldValue

private[ror] object AuditFieldValue {

  // Rule
  case object IsMatched extends AuditFieldValue

  case object FinalState extends AuditFieldValue

  case object Reason extends AuditFieldValue

  case object User extends AuditFieldValue

  case object ImpersonatedByUser extends AuditFieldValue

  case object Action extends AuditFieldValue

  case object InvolvedIndices extends AuditFieldValue

  case object AclHistory extends AuditFieldValue

  case object ProcessingDurationMillis extends AuditFieldValue

  // Identifiers
  case object Timestamp extends AuditFieldValue

  case object Id extends AuditFieldValue

  case object CorrelationId extends AuditFieldValue

  case object TaskId extends AuditFieldValue

  // Error details
  case object ErrorType extends AuditFieldValue

  case object ErrorMessage extends AuditFieldValue

  case object Type extends AuditFieldValue

  // HTTP protocol values
  case object HttpMethod extends AuditFieldValue

  case object HttpHeaderNames extends AuditFieldValue

  case object HttpPath extends AuditFieldValue

  case object XForwardedForHttpHeader extends AuditFieldValue

  case object RemoteAddress extends AuditFieldValue

  case object LocalAddress extends AuditFieldValue

  case object Content extends AuditFieldValue

  case object ContentLengthInBytes extends AuditFieldValue

  case object ContentLengthInKb extends AuditFieldValue

  // ES environment

  case object EsNodeName extends AuditFieldValue

  case object EsClusterName extends AuditFieldValue

  // Technical

  final case class StaticText(value: String) extends AuditFieldValue

  final case class Combined(values: List[AuditFieldValue]) extends AuditFieldValue

}
