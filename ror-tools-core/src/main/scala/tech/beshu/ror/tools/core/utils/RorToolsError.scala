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
package tech.beshu.ror.tools.core.utils

import tech.beshu.ror.tools.core.patches.internal.filePatchers.JarManifestModifier.PatchedJarFile

sealed class RorToolsError(val message: String)

object RorToolsError {

  private val patchingDocumentationUrl = "https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch"

  final case class EsPatchedWithDifferentVersionError(expectedRorVersion: String, patchedByRorVersion: String)
    extends RorToolsError(s"Elasticsearch was patched using ROR $patchedByRorVersion patcher. It should be unpatched using ROR $patchedByRorVersion and patched again with current ROR patcher. ReadonlyREST cannot be started. For patching instructions see our docs: $patchingDocumentationUrl")

  final case class PatchPerformedOnOtherEsVersionError(currentEsVersion: String, patchPerformedOnEsVersion: String)
    extends RorToolsError(
      s"""The patch was performed on Elasticsearch version $patchPerformedOnEsVersion, but currently installed ES version is $currentEsVersion.
         |As a result, the Elasticsearch is in a corrupted state. Please consider reinstalling it.
         |To avoid this issue in the future, please follow those steps when upgrading ES:
         | 1. Unpatch the older ES version using ror-tools
         | 2. Upgrade to the newer ES version
         | 3. Patch ES after the upgrade using ror-tools
         |For patching instructions see our docs: $patchingDocumentationUrl""".stripMargin
    )

  object EsNotPatchedError
    extends RorToolsError(s"Elasticsearch is NOT patched. ReadonlyREST cannot be used yet. For patching instructions see our docs: $patchingDocumentationUrl")

  final case class EsAlreadyPatchedError(rorVersion: String)
    extends RorToolsError(s"Elasticsearch is already patched with current version $rorVersion")

  final case class CorruptedPatchWithoutValidMetadataError(backupFolderIsPresent: Boolean, patchedJarFiles: List[PatchedJarFile])
    extends RorToolsError(
      s"""Elasticsearch is likely patched by an older version of ROR, but there is no valid patch metadata present. In case of problems please try to unpatch using the ROR version that had been used for patching or reinstall ES.
         |${if (backupFolderIsPresent) " - backup catalog is present, but there is no metadata file" else " - there is no backup catalog"}
         |${patchedJarFiles.sortBy(_.name).map(f => s" - file ${f.name} was patched by ROR ${f.patchedByRorVersion}").mkString("\n")}""".stripMargin)

  final case class CorruptedPatchWithIllegalFileModificationsDetectedError(files: List[os.Path])
    extends RorToolsError(s"Elasticsearch was patched, but files ${files.map(_.toString).mkString(",")} were modified after patching")

}
