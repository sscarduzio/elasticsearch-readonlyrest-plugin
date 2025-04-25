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
package tech.beshu.ror.tools.core.patches.base

import tech.beshu.ror.tools.core.patches.*
import tech.beshu.ror.tools.core.patches.internal.FilePatch.FilePatchMetadata
import tech.beshu.ror.tools.core.patches.internal.{EsNotRequirePatch, RorPluginDirectory}
import tech.beshu.ror.tools.core.utils.EsDirectory
import tech.beshu.ror.tools.core.utils.EsUtil.*

trait EsPatch {

  def performPatching(): List[FilePatchMetadata]

  def performBackup(): Unit

  def performRestore(): Unit

}

object EsPatch {

  def create(esDirectory: EsDirectory): EsPatch = {
    val rorPluginDirectory = new RorPluginDirectory(esDirectory)
    readEsVersion(esDirectory) match {
      case esVersion if esVersion >= es900 => new Es90xPatch(rorPluginDirectory, esVersion)
      case esVersion if esVersion == es900rc1 => new Es900rc1Patch(rorPluginDirectory, esVersion)
      case esVersion if esVersion >= es8180 => new Es90xPatch(rorPluginDirectory, esVersion)
      case esVersion if esVersion >= es8150 => new Es815xPatch(rorPluginDirectory, esVersion)
      case esVersion if esVersion >= es8140 => new Es814xPatch(rorPluginDirectory, esVersion)
      case esVersion if esVersion >= es8130 => new Es813xPatch(rorPluginDirectory, esVersion)
      case esVersion if esVersion >= es890 => new Es89xPatch(rorPluginDirectory, esVersion)
      case esVersion if esVersion >= es830 => new Es83xPatch(rorPluginDirectory, esVersion)
      case esVersion if esVersion >= es800 => new Es80xPatch(rorPluginDirectory, esVersion)
      case esVersion if esVersion >= es71713 => new Es717xPatch(rorPluginDirectory, esVersion)
      case esVersion if esVersion >= es7110 => new Es711xPatch(rorPluginDirectory, esVersion)
      case esVersion if esVersion >= es700 => new Es70xPatch(rorPluginDirectory, esVersion)
      case esVersion if esVersion >= es670 => new Es67xPatch(rorPluginDirectory, esVersion)
      case esVersion => new EsNotRequirePatch(esVersion)
    }
  }
}
