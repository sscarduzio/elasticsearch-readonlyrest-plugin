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
package tech.beshu.ror.utils

import org.joor.Reflect.on

import scala.util.Try

object ReflectUtils {

  implicit class AnyOps[T](val anObject: T) extends AnyVal {

    def transform[V](fieldName: String, func: V => Option[V]): Try[T] = {
      Try(on(anObject).get[V](fieldName))
        .map { value =>
          func(value) match {
            case Some(newValue) =>
              on(anObject).set(fieldName, newValue)
              anObject
            case None =>
              anObject
          }
        }
    }
  }
}
