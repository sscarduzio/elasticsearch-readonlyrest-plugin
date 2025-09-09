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
import tech.beshu.ror.configuration.{MainRorSettings, RawRorSettings, RawRorSettingsYamlParser}
import tech.beshu.ror.es.IndexDocumentManager
import tech.beshu.ror.settings.source.MainSettingsIndexSource.Const

class MainSettingsIndexSource private(indexDocumentManager: IndexDocumentManager,
                                      settingsIndex: RorSettingsIndex)
                                     (implicit codec: Codec[MainRorSettings])
  extends IndexSettingsSource[MainRorSettings](indexDocumentManager, settingsIndex.index, documentId = Const.id)

object MainSettingsIndexSource {

  def create(indexJsonContentService: IndexDocumentManager,
             settingsIndex: RorSettingsIndex,
             settingsYamlParser: RawRorSettingsYamlParser): MainSettingsIndexSource = {
    implicit val codec: Codec[MainRorSettings] = mainRorSettingsCodec(settingsYamlParser)
    new MainSettingsIndexSource(indexJsonContentService, settingsIndex)
  }

  private object Const {
    val id = "1"
    val settingsKey = "settings"
  }

  private def mainRorSettingsCodec(settingsYamlParser: RawRorSettingsYamlParser): Codec[MainRorSettings] = {
    implicit val codec: Codec[RawRorSettings] = new RawRorSettingsCodec(settingsYamlParser)
    Codec.forProduct1[MainRorSettings, RawRorSettings](Const.settingsKey)(MainRorSettings.apply)(_.rawSettings)
  }
}