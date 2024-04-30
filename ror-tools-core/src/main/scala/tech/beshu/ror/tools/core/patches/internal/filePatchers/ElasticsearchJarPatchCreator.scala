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
package tech.beshu.ror.tools.core.patches.internal.filePatchers

import just.semver.SemVer
import tech.beshu.ror.tools.core.patches.internal.modifiers.FileModifier
import tech.beshu.ror.tools.core.patches.internal.{FileModifiersBasedPatch, FilePatch, RorPluginDirectory}

private[patches] class ElasticsearchJarPatchCreator(patchingSteps: FileModifier*)
  extends FilePatchCreator[ElasticsearchJarPatch] {

  override def create(rorPluginDirectory: RorPluginDirectory,
                      esVersion: SemVer): ElasticsearchJarPatch =
    new ElasticsearchJarPatch(rorPluginDirectory, esVersion, patchingSteps)
}

private[patches] class ElasticsearchJarPatch(rorPluginDirectory: RorPluginDirectory,
                                             esVersion: SemVer,
                                             patchingSteps: Iterable[FileModifier])
  extends FileModifiersBasedPatch(
    rorPluginDirectory = rorPluginDirectory,
    fileToPatchPath = rorPluginDirectory.esDirectory.libPath / s"elasticsearch-${esVersion.render}.jar",
    patchingSteps = patchingSteps
  )