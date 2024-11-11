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
package tech.beshu.ror.utils.json

import tech.beshu.ror.com.jayway.jsonpath.JsonPath as JaywayJsonPath
import tech.beshu.ror.utils.json.JsonPath.UnableToReadInputAtJsonPath

import scala.util.{Failure, Try}


final case class JsonPath private(rawPath: String) {
  private val path: JaywayJsonPath = JsonPath.compile(rawPath)

  def read[A](json: String): Try[A] = tryRead(path.read[A](json))

  def read[A](obj: Object): Try[A] = tryRead(path.read[A](obj))

  private def tryRead[A](value: => A): Try[A] =
    Try(value)
      .recoverWith {
        case cause: Throwable => Failure(UnableToReadInputAtJsonPath(cause))
      }
}

object JsonPath {
  def apply(value: String): Try[JsonPath] = {
    Try(compile(value))
      .recoverWith {
        case cause: Throwable => Failure(UnableToCompileJsonPath(cause))
      }
      .map(_ => new JsonPath(value))
  }

  private def compile(path: String): JaywayJsonPath = JaywayJsonPath.compile(path)

  private final case class UnableToCompileJsonPath(cause: Throwable) extends Exception("Unable to compile JSON path", cause)
  private final case class UnableToReadInputAtJsonPath(cause: Throwable) extends Exception("Unable to read input at JSON path", cause)
}
