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
      extends RorToolsError(
        s"Elasticsearch was patched using ROR $patchedByRorVersion patcher. It should be unpatched using ROR $patchedByRorVersion and patched again with current ROR patcher. ReadonlyREST cannot be started. For patching instructions see our docs: $patchingDocumentationUrl"
      )

  final case class PatchPerformedOnOtherEsVersionError(currentEsVersion: String, patchPerformedOnEsVersion: String)
      extends RorToolsError(
        s"""The patch was performed on Elasticsearch version $patchPerformedOnEsVersion, but currently installed ES version is $currentEsVersion.
           |As a result, the Elasticsearch is in a corrupted state. ES must be reinstalled.
           |To avoid this issue in the future, please follow those steps when upgrading ES:
           | 1. Unpatch the older ES version using ror-tools
           | 2. Upgrade to the newer ES version
           | 3. Patch ES after the upgrade using ror-tools
           |For patching instructions see our docs: $patchingDocumentationUrl""".stripMargin
      )

  object EsNotPatchedError
      extends RorToolsError(
        s"Elasticsearch is NOT patched. ReadonlyREST cannot be used yet. For patching instructions see our docs: $patchingDocumentationUrl"
      )

  final case class EsAlreadyPatchedError(rorVersion: String)
      extends RorToolsError(s"Elasticsearch is already patched with current version $rorVersion")

  final case class CorruptedPatchWithoutValidMetadataError(
      backupFolderIsPresent: Boolean,
      patchedJarFiles: List[PatchedJarFile]
  ) extends RorToolsError(
        s"""Elasticsearch is either patched by an older version of ROR or corrupted.
           | - if ES has been patched using some older ROR version, then try unpatching using that older ROR version
           | - otherwise the ES installation is corrupted and ES must be reinstalled
           |Problems:
           |${
            if (backupFolderIsPresent) " - backup catalog is present, but there is no metadata file"
            else " - there is no backup catalog"
          }
           |${patchedJarFiles
            .sortBy(_.name)
            .map(f => s" - file ${f.name} was patched by ROR ${f.patchedByRorVersion}")
            .mkString("\n")}""".stripMargin
      )

  final case class PatchMetadataInaccessibleError(metadataFile: os.Path)
      extends RorToolsError(
        s"""Cannot read the ROR patch metadata file $metadataFile. Elasticsearch is possibly patched, but the current user has no permission to read this file.
           |Make sure that the user that runs Elasticsearch can read the ${metadataFile / os.up} folder and its content, and the files that ror-tools created in the ${metadataFile / os.up / os.up} folder.
           |$permissionProblemAdvice""".stripMargin
      )

  final case class PatchedFilesInaccessibleError(files: List[os.Path])
      extends RorToolsError(
        s"""Cannot read the files that ROR patched: ${files.mkString(",")}.
           |Elasticsearch is patched, but the current user has no permission to read these files.
           |Make sure that the user that runs Elasticsearch can read these files.
           |$permissionProblemAdvice""".stripMargin
      )

  private def permissionProblemAdvice =
    s"""If you run ror-tools (patch, unpatch or verify), run it as the user that patched Elasticsearch (e.g. root).
       |This problem can occur when ES was patched with a restrictive umask (e.g. 077 with sudo).
       |For patching instructions see our docs: $patchingDocumentationUrl""".stripMargin

  final case class CorruptedPatchWithIllegalFileModificationsDetectedError(files: List[os.Path])
      extends RorToolsError(
        s"Elasticsearch was patched, but files ${files.map(_.toString).mkString(",")} were modified after patching"
      )

}
