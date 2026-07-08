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
package tech.beshu.ror.utils.misc

// Corretto versions replacing ES's buggy bundled JDKs (JDK-8287073); downloaded INSIDE the docker
// build (single RUN, no persisted tarball layer) — see Elasticsearch.replaceBundledJdk.
object JDK {

  val corretto17Version = "17.0.5.8.1"
  val corretto19Version = "19.0.0.36.1"

  // ${JDK_ARCH} is left for the RUN's shell to expand (aarch64|x64 from `uname -m` in-container).
  def correttoDownloadUrlTemplate(version: String): String =
    s"https://corretto.aws/downloads/resources/$version/amazon-corretto-$version-linux-$${JDK_ARCH}.tar.gz"

}
