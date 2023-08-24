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
package tech.beshu.ror.accesscontrol.domain

import cats.data.NonEmptyList
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import io.lemonlabs.uri.Uri
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable

import java.util.regex
import scala.util.Try

final case class RorAuditLoggerName(value: NonEmptyString)
object RorAuditLoggerName {
  val default: RorAuditLoggerName = RorAuditLoggerName("readonlyrest_audit")
}

sealed trait AccessRequirement[T] {
  def value: T
}
object AccessRequirement {
  final case class MustBePresent[T](override val value: T) extends AccessRequirement[T]
  final case class MustBeAbsent[T](override val value: T) extends AccessRequirement[T]
}

sealed trait AuditCluster
object AuditCluster {
  case object LocalAuditCluster extends AuditCluster
  final case class RemoteAuditCluster(uris: NonEmptyList[Uri]) extends AuditCluster
}

final case class Regex private(value: String) {
  val pattern: regex.Pattern = regex.Pattern.compile(value)
}
object Regex {
  private val specialChars = """<([{\^-=$!|]})?*+.>"""

  def compile(value: String): Try[Regex] = Try(new Regex(value))
  def buildFromLiteral(value: String): Regex = {
    val escapedValue = value
      .map {
        case c if specialChars.contains(c) => s"""\\$c"""
        case c => c
      }
      .mkString
    new Regex(s"^$escapedValue$$")
  }
}

object Json {

  type JsonRepresentation = JsonTree[JsonValue]
  type ResolvableJsonRepresentation = JsonTree[RuntimeSingleResolvableVariable[JsonValue]]

  sealed trait JsonTree[+T]
  object JsonTree {
    final case class Object[T](fields: Map[String, JsonTree[T]]) extends JsonTree[T]
    final case class Array[T](elements: List[JsonTree[T]]) extends JsonTree[T]
    final case class Value[T](value: T) extends JsonTree[T]
  }

  sealed trait JsonValue
  object JsonValue {
    final case class StringValue(value: String) extends JsonValue
    final case class NumValue(value: Double) extends JsonValue
    final case class BooleanValue(value: Boolean) extends JsonValue
    case object NullValue extends JsonValue
  }
}