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

object AmazonCorretto1703jdk extends LazyLogging {

  lazy val tarball: File = {
    val targetFile = File.newTemporaryFile("amazon-corretto-17-jdk", ".tar.gz")
    logger.info("Downloading Amazon Corretto 17 JDK (one-time, for replacing buggy bundled JDK)...")
    for {
      in <- downloadJdk().getInputStream.autoClosed
      out <- targetFile.newOutputStream.autoClosed
    } in.pipeTo(out)
    logger.info(s"Downloaded Amazon Corretto 17 JDK to ${targetFile.pathAsString}")
    targetFile
  }

  private def downloadJdk() = {
    val arch = System.getProperty("os.arch") match {
      case a if a == "aarch64" || a == "arm64" => "aarch64"
      case _ => "x64"
    }
    val url = new java.net.URL(s"https://corretto.aws/downloads/resources/17.0.4.9.1/amazon-corretto-17.0.4.9.1-linux-$arch.tar.gz")
    val connection = url.openConnection()
    connection.setConnectTimeout(30_000)
    connection.setReadTimeout(120_000)
    connection
  }
}
