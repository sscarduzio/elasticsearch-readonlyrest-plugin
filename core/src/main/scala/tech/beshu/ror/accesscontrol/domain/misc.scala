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

import eu.timepit.refined.auto.*
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.utils.js.JsRegexCompiler

import java.util.regex
import scala.util.Try

final case class RequestId(value: String)

sealed trait CaseSensitivity

object CaseSensitivity {
  case object Enabled extends CaseSensitivity
  case object Disabled extends CaseSensitivity

  // in order to call from java
  def enabled: Enabled.type = Enabled
  def disabled: Disabled.type = Disabled
}

sealed trait AccessRequirement[T] {
  def value: T
}

object AccessRequirement {
  final case class MustBePresent[T](override val value: T) extends AccessRequirement[T]
  final case class MustBeAbsent[T](override val value: T) extends AccessRequirement[T]
}

final case class JavaRegex private (value: String) {
  val pattern: regex.Pattern = regex.Pattern.compile(value)
}

object JavaRegex {
  private val specialChars = """<([{\^-=$!|]})?*+.>"""

  def compile(value: String): Try[JavaRegex] = Try(new JavaRegex(value))

  def buildFromLiteral(value: String): JavaRegex = {
    val escapedValue = value.map {
      case c if specialChars.contains(c) => s"""\\$c"""
      case c                             => c
    }.mkString
    new JavaRegex(s"^$escapedValue$$")
  }

}

final case class JsRegex private (value: NonEmptyString)

object JsRegex {
  private val extractRawRegex = """\/(.*)\/""".r

  def compile(str: NonEmptyString)(
      implicit jsRegexCompiler: JsRegexCompiler
  ): Either[CompilationResult, JsRegex] = {
    if (isNotMultilineString(str)) {
      str.value match {
        case extractRawRegex(regex) =>
          jsRegexCompiler
            .compile(regex)
            .map(_ => new JsRegex(str))
            .left
            .map(CompilationResult.SyntaxError.apply)
        case _ =>
          Left(CompilationResult.NotRegex)
      }
    } else {
      Left(CompilationResult.SyntaxError("the regex contains a new line"))
    }
  }

  private def isNotMultilineString(str: NonEmptyString) = !str.contains("\n")

  sealed trait CompilationResult

  object CompilationResult {
    case object NotRegex extends CompilationResult
    final case class SyntaxError(reason: String) extends CompilationResult
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

/**
 * Caches a case class's `hashCode` eagerly. Mixed into small immutable domain identities that
 * appear at high frequency in `Set`/`Map` operations on the hot ACL request-evaluation path, so
 * their `hashCode` is computed once instead of being recomputed on every lookup/insertion.
 *
 * The cached value is identical to the compiler-generated case class `hashCode`
 * (`MurmurHash3.caseClassHash`), so it stays consistent with structural `equals` and changes no
 * equality semantics — only when the hash is computed.
 */
private[domain] trait EagerHashCode { this: Product =>
  override val hashCode: Int = scala.util.hashing.MurmurHash3.caseClassHash(this)
}
