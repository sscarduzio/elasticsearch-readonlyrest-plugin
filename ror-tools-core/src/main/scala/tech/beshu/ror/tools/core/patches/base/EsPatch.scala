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

import tech.beshu.ror.tools.core.patches.internal.{EsNotRequirePatch, EsPatchLoggingDecorator, RorPluginDirectory}
import tech.beshu.ror.tools.core.patches._
import tech.beshu.ror.tools.core.patches.base.EsPatch.IsPatched
import tech.beshu.ror.tools.core.patches.base.EsPatch.IsPatched.No.Cause
import tech.beshu.ror.tools.core.patches.base.EsPatch.IsPatched.{No, Yes}
import tech.beshu.ror.tools.core.utils.EsDirectory
import tech.beshu.ror.tools.core.utils.EsUtil._

trait EsPatch {

  def isPatched: IsPatched

  def backup(): Unit

  def restore(): Unit

  def execute(): Unit

  protected def checkWithPatchedByFile(rorPluginDirectory: RorPluginDirectory): IsPatched = {
    val currentRorVersion = rorPluginDirectory.readCurrentRorVersion()
    rorPluginDirectory.readPatchedByRorVersion() match {
      case None =>
        Yes // previous version doesn't have "patched_by" version written, so, we assume it's patched
      case Some(patchedByRorVersion) if patchedByRorVersion == currentRorVersion =>
        Yes
      case Some(patchedByRorVersion) =>
        No(
          Cause.PatchedWithDifferentVersion(
            expectedRorVersion = currentRorVersion,
            patchedByRorVersion = patchedByRorVersion
          )
        )
    }
  }
}
object EsPatch {

  sealed trait IsPatched
  object IsPatched {
    case object Yes extends IsPatched
    final case class No(cause: Cause) extends IsPatched
    object No {
      sealed trait Cause {
        def message: String
      }
      object Cause {
        private val patchingDocumentationUrl = "https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch"

        final case class PatchedWithDifferentVersion(expectedRorVersion: String, patchedByRorVersion: String) extends Cause {
          override val message: String = s"Elasticsearch was patched using ROR $patchedByRorVersion patcher. It should be unpatched and patched again with current ROR patcher ($expectedRorVersion). ReadonlyREST cannot be started. For patching instructions see our docs: $patchingDocumentationUrl"
        }
        case object NotPatchedAtAll extends Cause {
          override val message: String = s"Elasticsearch is NOT patched. ReadonlyREST cannot be used yet. For patching instructions see our docs: $patchingDocumentationUrl"
        }
      }
    }

    implicit class ToBoolean(val isPatched: IsPatched) extends AnyVal {
      def toBoolean: Boolean = isPatched match {
        case Yes => true
        case No(_) => false
      }
    }
  }

  def create(esPath: os.Path): EsPatch = {
    create(EsDirectory.from(esPath))
  }

  def create(esDirectory: EsDirectory): EsPatch = {
    val rorPluginDirectory = new RorPluginDirectory(esDirectory)
    new EsPatchLoggingDecorator(
      readEsVersion(esDirectory) match {
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
    )
  }
}