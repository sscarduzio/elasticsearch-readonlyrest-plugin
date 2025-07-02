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
package tech.beshu.ror.configuration.loader.distributed

import cats.data.EitherT
import monix.eval.Task
import tech.beshu.ror.SystemContext
import tech.beshu.ror.configuration.RorConfigLoading.LoadRorConfig
import tech.beshu.ror.configuration.EsConfig.RorEsLevelSettings.LoadingRorCoreStrategy
import tech.beshu.ror.configuration.index.IndexConfigManager
import tech.beshu.ror.configuration.loader.{RorConfigLoadingInterpreter, LoadRawRorConfig, LoadedRorConfig}
import tech.beshu.ror.configuration.{RorConfigLoading, RawRorConfig}
import tech.beshu.ror.es.EsEnv

object RawRorConfigLoadingAction {

  // todo: IndexConfigManager or maybe not?
  def loadFromIndex(env: EsEnv, indexConfigManager: IndexConfigManager)
                   (implicit systemContext: SystemContext): Task[Either[LoadedRorConfig.Error, LoadedRorConfig[RawRorConfig]]] = {
    val compiler = RorConfigLoadingInterpreter.create(indexConfigManager)
    (for {
      esConfig <- EitherT(RorConfigLoading.loadEsConfig(env))
      loadedConfig <- esConfig.rorEsLevelSettings.loadingRorCoreStrategy match {
        case LoadingRorCoreStrategy.ForceLoadingFromFile(_) =>
          EitherT.leftT[LoadRorConfig, LoadedRorConfig[RawRorConfig]](
            LoadedRorConfig.CannotUseRorConfigurationWhenXpackSecurityIsEnabled("todo"): LoadedRorConfig.Error // todo: fixme
          )
        case LoadingRorCoreStrategy.LoadFromIndexWithFileFallback(settings, _) =>
          EitherT(LoadRawRorConfig.loadFromIndex(settings))
      }
    } yield loadedConfig).value.foldMap(compiler)
  }

}
