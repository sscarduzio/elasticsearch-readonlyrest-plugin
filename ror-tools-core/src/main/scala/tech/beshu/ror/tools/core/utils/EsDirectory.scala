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

import os.Path
import tech.beshu.ror.tools.core.utils.RorToolsException.EsPathException

import scala.util.{Failure, Success, Try}

sealed case class EsDirectory private(path: os.Path)
object EsDirectory {

  val defaultPath: Path = os.Path("/usr/share/elasticsearch")

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

