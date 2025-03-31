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

import just.semver.SemVer

sealed trait RorToolsException {
  this: Throwable =>
}

object RorToolsException {

  private val patchingDocumentationUrl = "https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch"

  final case class EsPatchedWithDifferentVersionException(expectedRorVersion: String, patchedByRorVersion: String)
    extends IllegalStateException(s"Elasticsearch was patched using ROR $patchedByRorVersion patcher. It should be unpatched and patched again with current ROR patcher. ReadonlyREST cannot be started. For patching instructions see our docs: $patchingDocumentationUrl") with RorToolsException

  object EsNotPatchedException
    extends IllegalStateException(s"Elasticsearch is NOT patched. ReadonlyREST cannot be used yet. For patching instructions see our docs: $patchingDocumentationUrl") with RorToolsException

  final case class EsAlreadyPatchedException(rorVersion: String)
    extends IllegalStateException(s"Elasticsearch is already patched with current version $rorVersion") with RorToolsException

  final class EsPatchingNotRequired(esVersion: SemVer)
    extends IllegalStateException(s"Elasticsearch ${esVersion.render} doesn't require patching")
      with RorToolsException

  final case class EsPathException(message: String)
    extends IllegalStateException(
      s"$message. Consider using --es-path to specify custom Elasticsearch installation path. " +
        s"See our docs for details: https://docs.readonlyrest.com/elasticsearch#installing-the-plugin"
    )
      with RorToolsException
}
