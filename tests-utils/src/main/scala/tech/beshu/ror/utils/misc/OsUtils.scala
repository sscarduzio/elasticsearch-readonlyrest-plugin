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

import com.dimafeng.testcontainers.Container
import com.typesafe.scalalogging.LazyLogging

import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

object OsUtils extends LazyLogging {

  def currentOs: CurrentOs =
    if (isWindows) CurrentOs.Windows
    else CurrentOs.OtherThanWindows

  def ignoreOnWindows[T](f: => T): Any = {
    if (!isWindows) f
  }

  def doNotCreateOnWindows(creator: => Container): Container = {
    if (!isWindows) creator else NoOpContainer
  }

  // The same method is present in ror-tools-core module.
  // It is copied instead of reused, because there is no common module between integration tests and the ror-tools production code.
  private def isWindows: Boolean = {
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

  sealed trait CurrentOs

  object CurrentOs {
    case object Windows extends CurrentOs

    case object OtherThanWindows extends CurrentOs
  }

  private object NoOpContainer extends Container {
    override def start(): Unit = ()

    override def stop(): Unit = ()
  }

}
