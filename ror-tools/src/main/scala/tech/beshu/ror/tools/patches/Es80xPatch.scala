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

import tech.beshu.ror.tools.utils.EsUtil
import tech.beshu.ror.tools.utils.EsUtil.findTransportNetty4JarIn

import scala.language.postfixOps
import scala.util.Try

class Es80xPatch(esPath: os.Path) extends EsPatch {

  private val transportNetty4ModulePath = esPath / "modules" / "transport-netty4"
  private val readonlyRestPluginPath = EsUtil.readonlyrestPluginPath(esPath)

  override def assertIsPatched(): Try[Unit] = Try {
    findTransportNetty4JarIn(readonlyRestPluginPath) match {
      case Some(_) => ()
      case None => throw new IllegalStateException("ReadonlyREST plugin is not patched yet")
    }
  }

  override def backup(): Try[Unit] = Try {
    // nothing to do
  }

  override def restore(): Try[Unit] = Try {
    findTransportNetty4JarIn(readonlyRestPluginPath) match {
      case Some(jar) => os.remove(jar)
      case None => throw new IllegalStateException("ReadonlyREST plugin is not patched yet")
    }
  }

  override def execute(): Try[Unit] = Try {
    findTransportNetty4JarIn(transportNetty4ModulePath) match {
      case Some(jar) =>
        os.copy(from = jar, to = readonlyRestPluginPath / jar.last)
      case None =>
        new IllegalStateException(s"ReadonlyREST plugin cannot be patched due to not found transport netty4 jar")
    }
  }
}
