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

import enumeratum._

import scala.collection.immutable.IndexedSeq

sealed trait AuditFieldValuePlaceholder extends EnumEntry.UpperSnakecase

object AuditFieldValuePlaceholder extends Enum[AuditFieldValuePlaceholder] {

  // Rule
  case object IsMatched extends AuditFieldValuePlaceholder

  case object FinalState extends AuditFieldValuePlaceholder

  case object Reason extends AuditFieldValuePlaceholder

  case object User extends AuditFieldValuePlaceholder

  case object ImpersonatedByUser extends AuditFieldValuePlaceholder

  case object Action extends AuditFieldValuePlaceholder

  case object InvolvedIndices extends AuditFieldValuePlaceholder

  case object AclHistory extends AuditFieldValuePlaceholder

  case object ProcessingDurationMillis extends AuditFieldValuePlaceholder

  // Identifiers
  case object Timestamp extends AuditFieldValuePlaceholder

  case object Id extends AuditFieldValuePlaceholder

  case object CorrelationId extends AuditFieldValuePlaceholder

  case object TaskId extends AuditFieldValuePlaceholder

  // Error details
  case object ErrorType extends AuditFieldValuePlaceholder

  case object ErrorMessage extends AuditFieldValuePlaceholder

  case object Type extends AuditFieldValuePlaceholder

  // HTTP protocol values
  case object HttpMethod extends AuditFieldValuePlaceholder

  case object HttpHeaderNames extends AuditFieldValuePlaceholder

  case object HttpPath extends AuditFieldValuePlaceholder

  case object XForwardedForHttpHeader extends AuditFieldValuePlaceholder

  case object RemoteAddress extends AuditFieldValuePlaceholder

  case object LocalAddress extends AuditFieldValuePlaceholder

  case object Content extends AuditFieldValuePlaceholder

  case object ContentLengthInBytes extends AuditFieldValuePlaceholder

  case object ContentLengthInKb extends AuditFieldValuePlaceholder

  // Environment
  case object EsNodeName extends AuditFieldValuePlaceholder

  case object EsClusterName extends AuditFieldValuePlaceholder

  final case class StaticText(value: String) extends AuditFieldValuePlaceholder

  override def values: IndexedSeq[AuditFieldValuePlaceholder] = findValues

}
