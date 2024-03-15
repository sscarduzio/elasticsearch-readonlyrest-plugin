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

object EsUtil {

  private val elasticsearchJar = """^elasticsearch-(\d+\.\d+\.\d+)\.jar$""".r
  private val transportNetty4JarNameRegex = """^transport-netty4-\d+\.\d+\.\d+\.jar$""".r

  val es890: SemVer = SemVer.unsafeParse("8.9.0")
  val es830: SemVer = SemVer.unsafeParse("8.3.0")
  val es820: SemVer = SemVer.unsafeParse("8.2.0")
  val es810: SemVer = SemVer.unsafeParse("8.1.0")
  val es800: SemVer = SemVer.unsafeParse("8.0.0")
  val es71713: SemVer = SemVer.unsafeParse("7.17.13")
  val es790: SemVer = SemVer.unsafeParse("7.9.0")
  val es780: SemVer = SemVer.unsafeParse("7.8.0")
  val es700: SemVer = SemVer.unsafeParse("7.0.0")
  val es640: SemVer = SemVer.unsafeParse("6.4.0")
  val es650: SemVer = SemVer.unsafeParse("6.5.0")

  def readEsVersion(esDirectory: EsDirectory): SemVer = {
    os
      .list(esDirectory.path / "lib")
      .view
      .flatMap { file =>
        file.last match {
          case elasticsearchJar(version) => SemVer.parse(version).toOption
          case _ => None
        }
      }
      .headOption
      .getOrElse {
        throw new IllegalArgumentException("Cannot determine Elasticsearch version")
      }
  }

  def findTransportNetty4JarIn(path: os.Path): Option[Path] = {
    os
      .list(path)
      .filter { file => file.last.matches(transportNetty4JarNameRegex.pattern.pattern()) }
      .toList match {
      case Nil =>
        None
      case foundFile :: Nil =>
        Some(foundFile)
      case many =>
        throw new IllegalStateException(
          s"More than one file matching regex ${transportNetty4JarNameRegex.pattern.pattern()} in $path; Found: ${many.mkString(", ")}"
        )
    }
  }

  def readonlyrestPluginPath(esPath: os.Path): Path = esPath / "plugins" / "readonlyrest"
}
