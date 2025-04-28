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
import tech.beshu.ror.tools.core.patches.internal.FilePatch.FilePatchMetadata
import tech.beshu.ror.tools.core.patches.internal.{FileModifiersBasedPatch, FilePatch, RorPluginDirectory}

private[patches] object CopyTransportNetty4JarToPluginPatchCreator
  extends FilePatchCreator[CopyTransportNetty4JarToPluginPatch] {

  override def create(rorPluginDirectory: RorPluginDirectory,
                      esVersion: SemVer): CopyTransportNetty4JarToPluginPatch =
    new CopyTransportNetty4JarToPluginPatch(rorPluginDirectory)
}

private[patches] class CopyTransportNetty4JarToPluginPatch(rorPluginDirectory: RorPluginDirectory)
  extends FilePatch {

  override def backup(): Unit = ()

  override def patch(): List[FilePatch.FilePatchMetadata] = {
    rorPluginDirectory.esDirectory.findTransportNetty4Jar match {
      case Some(transportNetty4Jar) =>
        rorPluginDirectory.copyToPluginPath(transportNetty4Jar)
        rorPluginDirectory.findTransportNetty4Jar match {
          case Some(copiedJarFile) =>
            FileModifiersBasedPatch.addPatchedByRorVersionPropertyToJarManifest(rorPluginDirectory, copiedJarFile)
            List(FilePatchMetadata.forPath(copiedJarFile))
          case None =>
            throw new IllegalStateException(s"ReadonlyREST plugin cannot be patched due to transport netty4 jar not copied to ROR directory")
        }
      case None =>
        throw new IllegalStateException(s"ReadonlyREST plugin cannot be patched due to not found transport netty4 jar")
    }
  }

  override def restore(): Unit = {
    rorPluginDirectory.findTransportNetty4Jar.foreach {
      os.remove
    }
  }
}
