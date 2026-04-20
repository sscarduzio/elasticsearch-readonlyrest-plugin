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

import better.files.*
import com.typesafe.scalalogging.LazyLogging

object JDK extends LazyLogging {

  object AmazonCorretto1705jdk {
    lazy val tarball: File = downloadCorretto("17.0.5.8.1")
  }

  object AmazonCorretto1900jdk {
    lazy val tarball: File = downloadCorretto("19.0.0.36.1")
  }

  private def downloadCorretto(version: String): File = {
    val majorVersion = version.takeWhile(_ != '.')
    val arch = System.getProperty("os.arch") match {
      case a if a == "aarch64" || a == "arm64" => "aarch64"
      case _ => "x64"
    }
    val targetFile = File.newTemporaryFile(s"amazon-corretto-$majorVersion-jdk", ".tar.gz")
    logger.info(s"Downloading Amazon Corretto $majorVersion JDK (one-time, for replacing buggy bundled JDK)...")
    val url = new java.net.URI(s"https://corretto.aws/downloads/resources/$version/amazon-corretto-$version-linux-$arch.tar.gz").toURL
    val connection = url.openConnection()
    connection.setConnectTimeout(30_000)
    connection.setReadTimeout(120_000)
    for {
      in <- connection.getInputStream.autoClosed
      out <- targetFile.newOutputStream.autoClosed
    } in.pipeTo(out)
    logger.info(s"Downloaded Amazon Corretto $majorVersion JDK to ${targetFile.pathAsString}")
    targetFile
  }
}
