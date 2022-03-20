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
package tech.beshu.ror.tools.patches

import scala.util.Try

class Es8xxPatch(esPath: os.Path) extends EsPatch {

  private val transportNetty4JarNameRegex = """^transport-netty4-\d+\.\d+\.\d\.jar$""".r
  private val transportNetty4ModulePath = esPath / "modules" / "transport-netty4"
  private val readonlyrestPluginPath =  esPath / "plugins" / "readonlyrest"

  override def assertIsNotPatched(): Try[Unit] = {
    findTransportNetty4JarIn(readonlyrestPluginPath)
      .map {
        case Some(_) => throw new IllegalStateException("ReadonlyREST plugin is already patched")
        case None => ()
      }
  }

  override def assertIsPatched(): Try[Unit] = {
    findTransportNetty4JarIn(readonlyrestPluginPath)
      .map {
        case Some(_) => ()
        case None => throw new IllegalStateException("ReadonlyREST plugin is not patched yet")
      }
  }

  override def backup(): Try[Unit] = Try {
    // nothing to do
  }

  override def restore(): Try[Unit] = {
    findTransportNetty4JarIn(readonlyrestPluginPath)
      .map {
        case Some(jar) => os.remove(jar)
        case None => throw new IllegalStateException("ReadonlyREST plugin is not patched yet")
      }
  }

  override def execute(): Try[Unit] = {
    findTransportNetty4JarIn(transportNetty4ModulePath)
      .map {
        case Some(jar) =>
          os.copy(from = jar, to = readonlyrestPluginPath / jar.last)
        case None =>
          new IllegalStateException(s"ReadonlyREST plugin cannot be patched due to not found proper jar in $transportNetty4ModulePath")
      }
  }

  private def findTransportNetty4JarIn(path: os.Path) = Try {
    os
      .list(path)
      .filter { file => file.last.matches(transportNetty4JarNameRegex.pattern.pattern()) }
      .toList match {
      case Nil =>
        None
      case foundFile :: Nil =>
        Some(foundFile)
      case many =>
        throw new IllegalStateException(s"More than one file matching regex ${transportNetty4JarNameRegex.pattern.pattern()} in $path; Found: ${many.mkString(", ")}")
    }
  }
}

