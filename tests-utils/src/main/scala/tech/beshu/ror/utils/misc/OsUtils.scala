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
package tech.beshu.ror.utils.misc

import com.typesafe.scalalogging.LazyLogging

import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

object OsUtils extends LazyLogging {

  def isWindows: Boolean = {
    System.getProperties.stringPropertyNames().asScala
      .find { name =>
        // I have no idea why name == "os.name" doesn't work!
        name.length == 7 && name.indexOf("o") == 0 && name.endsWith("s.name")
      }
      .flatMap { osNamePropName =>
        Option(System.getProperty(osNamePropName))
      } match
      case Some(osName) => osName.toLowerCase.contains("win")
      case None => false
  }

  def doNotExecuteOnWindows[T](messageOnWindows: String, f: () => T): Any = {
    if (isWindows) {
      logger.warn(messageOnWindows)
      None
    } else {
      Some(f())
    }
  }

}
