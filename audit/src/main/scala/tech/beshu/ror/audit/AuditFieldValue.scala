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

final case class AuditFieldValue(value: List[AuditFieldValuePlaceholder])

object AuditFieldValue {

  private val pattern = "\\{([^}]+)\\}".r

  def apply(placeholder: AuditFieldValuePlaceholder): AuditFieldValue = AuditFieldValue(List(placeholder))

  def fromString(str: String): Either[String, AuditFieldValue] = {
    val matches = pattern.findAllMatchIn(str).toList

    val (parts, missing, lastIndex) =
      matches.foldLeft((List.empty[AuditFieldValuePlaceholder], List.empty[String], 0)) {
        case ((partsAcc, missingAcc, lastEnd), m) =>
          val key = m.group(1)
          val before = str.substring(lastEnd, m.start)
          val partBefore = if (before.nonEmpty) List(AuditFieldValuePlaceholder.StaticText(before)) else Nil

          val (partAfter, newMissing) = AuditFieldValuePlaceholder.withNameOption(key) match {
            case Some(placeholder) => (List(placeholder), Nil)
            case None => (Nil, List(key))
          }

          (partsAcc ++ partBefore ++ partAfter, missingAcc ++ newMissing, m.end)
      }

    val trailing = if (lastIndex < str.length) List(AuditFieldValuePlaceholder.StaticText(str.substring(lastIndex))) else Nil
    val allParts = parts ++ trailing

    missing match {
      case Nil => Right(AuditFieldValue(allParts))
      case missingList => Left(s"There are invalid placeholder values: ${missingList.mkString(", ")}")
    }
  }

}
