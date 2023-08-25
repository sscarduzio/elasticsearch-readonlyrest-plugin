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

import cats.Eq
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.domain.JsRegex.CompilationResult
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod

sealed trait KibanaApp
object KibanaApp {
  final case class FullNameKibanaApp(name: NonEmptyString) extends KibanaApp
  final case class KibanaAppRegex(regex: JsRegex) extends KibanaApp

  def from(str: NonEmptyString): Either[String, KibanaApp] = {
    JsRegex.compile(str) match {
      case Right(jsRegex) => Right(KibanaApp.KibanaAppRegex(jsRegex))
      case Left(CompilationResult.NotRegex) => Right(KibanaApp.FullNameKibanaApp(str))
      case Left(CompilationResult.SyntaxError) => Left(s"Cannot compile [${str.value}] as a JS regex (https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide/Regular_expressions)")
    }
  }

  implicit val eqKibanaApps: Eq[KibanaApp] = Eq.by {
    case FullNameKibanaApp(name) => name.value
    case KibanaAppRegex(regex) => regex.value.value
  }
}

final case class KibanaAllowedApiPath(httpMethod: AllowedHttpMethod, pathRegex: JavaRegex)
object KibanaAllowedApiPath {

  sealed trait AllowedHttpMethod
  object AllowedHttpMethod {
    case object Any extends AllowedHttpMethod
    final case class Specific(httpMethod: HttpMethod) extends AllowedHttpMethod

    sealed trait HttpMethod
    object HttpMethod {
      case object Get extends HttpMethod
      case object Post extends HttpMethod
      case object Put extends HttpMethod
      case object Delete extends HttpMethod
    }
  }
}

sealed trait KibanaAccess
object KibanaAccess {
  case object RO extends KibanaAccess
  case object RW extends KibanaAccess
  case object ROStrict extends KibanaAccess
  case object ApiOnly extends KibanaAccess
  case object Admin extends KibanaAccess
  case object Unrestricted extends KibanaAccess

  implicit val eqKibanaAccess: Eq[KibanaAccess] = Eq.fromUniversalEquals
}
