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
import os.Path
import tech.beshu.ror.tools.core.utils.EsUtil.findTransportNetty4JarIn
import tech.beshu.ror.tools.core.utils.RorToolsException.EsPathException

import scala.util.{Failure, Success, Try}

sealed case class EsDirectory private(path: os.Path) {

  private val elasticsearchJar = """^elasticsearch-(\d+\.\d+\.\d+(-[a-zA-Z0-9]+)?)\.jar$""".r

  val libPath: Path = path / "lib"
  val modulesPath: Path = path / "modules"

  def findTransportNetty4Jar: Option[Path] = {
    findTransportNetty4JarIn(path / "modules" / "transport-netty4")
  }

  def readEsVersion(): SemVer = {
    os
      .list(path / "lib")
      .view
      .flatMap { file =>
        file.last match {
          case elasticsearchJar(version, _) => SemVer.parse(version).toOption
          case _ => None
        }
      }
      .headOption
      .getOrElse {
        throw new IllegalArgumentException("Cannot determine Elasticsearch version")
      }
  }

}
object EsDirectory {

  def defaultPath: Path = os.root / "usr" / "share" / "elasticsearch"

  def from(path: os.Path): EsDirectory = {
    verifyEsLocation(path).get
  }

  def default: EsDirectory = {
    verifyEsLocation(os.pwd)
      .orElse(verifyEsLocation(defaultPath))
      .get
  }

  private def verifyEsLocation(esPath: os.Path): Try[EsDirectory] = {
    if (!esPath.toIO.exists()) {
      Failure(EsPathException(s"Directory $esPath doesn't exist!"))
    } else if (!esPath.toIO.isDirectory) {
      Failure(EsPathException(s"Directory $esPath is not a directory!"))
    } else if (!doesItLookLikeEsDirectory(esPath)) {
      Failure(EsPathException(s"Directory $esPath location doesn't look like Elasticsearch installation directory!"))
    } else {
      Success(EsDirectory(esPath))
    }
  }

  private def doesItLookLikeEsDirectory(esPath: os.Path) = {
    val contentOfEsDirectory = esPath.toIO.listFiles().toList
    contentOfEsDirectory.exists(_.getName == "modules") &&
      contentOfEsDirectory.exists(_.getName == "plugins") &&
      contentOfEsDirectory.exists(_.getName == "bin")
  }
}

