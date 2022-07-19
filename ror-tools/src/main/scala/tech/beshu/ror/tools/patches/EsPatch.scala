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

trait EsPatch {

  def isPatched: Boolean

  def backup(): Unit

  def restore(): Unit

  def execute(): Unit
}
object EsPatch {
  def create(esPath: os.Path): EsPatch = {
    new EsPatchLoggingDecorator(
      readEsVersion(esPath) match {
        case esVersion if esVersion < es800 => new EsNotRequirePatch(esVersion)
        case esVersion if esVersion < es830 => new Es80xPatch(esPath)
        case esVersion => new Es83xPatch(esPath, esVersion)
      }
    )
  }

  def createA(esPath: String): EsPatch = create(os.Path(esPath))
}