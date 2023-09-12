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
import tech.beshu.ror.tools.core.utils.EsUtil.{es630, es700, es71713, es800, es830, es890, readEsVersion}

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
        case esVersion if esVersion >= es890 =>   new Es89xPatch(esDirectory, esVersion)
        case esVersion if esVersion >= es830 =>   new Es83xPatch(esDirectory, esVersion)
        case esVersion if esVersion >= es800 =>   new Es80xPatch(esDirectory, esVersion)
        case esVersion if esVersion >= es71713 => new Es717xPatch(esDirectory, esVersion)
        case esVersion if esVersion >= es700 =>   new Es70xPatch(esDirectory, esVersion)
        case esVersion if esVersion >= es630 =>   new Es63xPatch(esDirectory, esVersion)
        case esVersion => new EsNotRequirePatch(esVersion)
      }
    )
  }
}