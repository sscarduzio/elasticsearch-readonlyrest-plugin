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

