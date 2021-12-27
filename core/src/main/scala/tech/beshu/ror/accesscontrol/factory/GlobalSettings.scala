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
package tech.beshu.ror.accesscontrol.factory

import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex

final case class GlobalSettings(showBasicAuthPrompt: Boolean,
                                forbiddenRequestMessage: String,
                                flsEngine: GlobalSettings.FlsEngine,
                                configurationIndex: RorConfigurationIndex,
                                usernameCaseMapping: GlobalSettings.UsernameCaseMapping)

object GlobalSettings {
  sealed trait UsernameCaseMapping
  object UsernameCaseMapping {
    case object CaseSensitive extends UsernameCaseMapping
    case object CaseInsensitive extends UsernameCaseMapping
  }

  sealed trait FlsEngine
  object FlsEngine {
    case object Lucene extends FlsEngine
    case object ESWithLucene extends FlsEngine
    case object ES extends FlsEngine

    val default = ESWithLucene
  }

  val defaultForbiddenRequestMessage: String = "forbidden"
}
