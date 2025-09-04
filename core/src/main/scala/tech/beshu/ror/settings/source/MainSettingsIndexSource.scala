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
package tech.beshu.ror.settings.source

import io.circe.Codec
import tech.beshu.ror.accesscontrol.domain.RorSettingsIndex
import tech.beshu.ror.configuration.RawRorSettings
import tech.beshu.ror.es.IndexJsonContentService
import tech.beshu.ror.settings.source.MainSettingsIndexSource.{Const, codec}

class MainSettingsIndexSource(indexJsonContentService: IndexJsonContentService,
                              settingsIndex: RorSettingsIndex)
  extends IndexSettingsSource[RawRorSettings](indexJsonContentService, settingsIndex, documentId = Const.id)

object MainSettingsIndexSource {
  private object Const {
    val id = "1"
    val settingsKey = "settings"
  }

  implicit val codec: Codec[RawRorSettings] = ???
}