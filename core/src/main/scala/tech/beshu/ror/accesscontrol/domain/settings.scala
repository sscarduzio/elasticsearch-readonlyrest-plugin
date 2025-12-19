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
package tech.beshu.ror.accesscontrol.domain

import better.files.File
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.utils.RefinedUtils.nes

final case class RorSettingsIndex(index: IndexName.Full) extends AnyVal {
  def toLocal: ClusterIndexName.Local = ClusterIndexName.Local(index)
}
object RorSettingsIndex {
  val default: RorSettingsIndex = RorSettingsIndex(IndexName.Full(nes(".readonlyrest")))
}

final case class RorSettingsFile(file: File) extends AnyVal
object RorSettingsFile {
  def default(esEnv: EsEnv): RorSettingsFile = RorSettingsFile(esEnv.configDir / "readonlyrest.yml")
}

final case class EsConfigFile(file: File) extends AnyVal
object EsConfigFile {
  def default(esEnv: EsEnv): EsConfigFile = EsConfigFile(esEnv.configDir / "elasticsearch.yml")
}