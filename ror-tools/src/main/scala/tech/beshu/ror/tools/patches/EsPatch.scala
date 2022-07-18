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

import tech.beshu.ror.tools.utils.EsUtil.{es800, es830, readEsVersion}

import scala.util.Try

trait EsPatch {

  def assertIsPatched(): Try[Unit]

  def backup(): Try[Unit]

  def restore(): Try[Unit]

  def execute(): Try[Unit]

  def assertIsNotPatched(): Try[Unit] = Try {
    assertIsPatched()
      .fold(
        _ => (),
        _ => throw new IllegalStateException("ReadonlyREST plugin is already patched")
      )
  }
}
object EsPatch {
  def create(esPath: os.Path): EsPatch = {
    readEsVersion(esPath) match {
      case esVersion if esVersion < es800 => NoOpPatch
      case esVersion if esVersion < es830 => new Es80xPatch(esPath)
      case esVersion => new Es83xPatch(esPath, esVersion)
    }
  }
}