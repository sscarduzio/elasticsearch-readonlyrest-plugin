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
package tech.beshu.ror.tools.core.patches

import tech.beshu.ror.tools.core.utils.EsDirectory
import tech.beshu.ror.tools.core.utils.EsUtil.{es700, es800, es830, readEsVersion}

trait EsPatch {

  def isPatched: Boolean

  def backup(): Unit

  def restore(): Unit

  def execute(): Unit
}
object EsPatch {

  def create(esPath: os.Path): EsPatch = {
    create(EsDirectory.from(esPath))
  }

  def create(esDirectory: EsDirectory): EsPatch = {
    new EsPatchLoggingDecorator(
      readEsVersion(esDirectory) match {
        case esVersion if esVersion >= es830 => new Es83xPatch(esDirectory, esVersion)
        case esVersion if esVersion >= es800 => new Es80xPatch(esDirectory, esVersion)
        case esVersion if esVersion >= es700 => new Es7xPatch(esDirectory, esVersion)
        case esVersion => new EsNotRequirePatch(esVersion)
      }
    )
  }
}