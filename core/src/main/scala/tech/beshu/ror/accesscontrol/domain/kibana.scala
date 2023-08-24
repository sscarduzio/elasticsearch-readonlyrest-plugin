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
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod

final case class KibanaApp(value: NonEmptyString)
object KibanaApp {
  implicit val eqKibanaApps: Eq[KibanaApp] = Eq.fromUniversalEquals
}

final case class KibanaAllowedApiPath(httpMethod: AllowedHttpMethod, pathRegex: Regex)
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
