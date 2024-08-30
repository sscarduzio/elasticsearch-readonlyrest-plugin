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
import tech.beshu.ror.configuration.index.IndexConfigManager
import tech.beshu.ror.configuration.loader.{ConfigLoadingInterpreter, LoadRawRorConfig, LoadedRorConfig}
import tech.beshu.ror.configuration.{ConfigLoading, EnvironmentConfig, RawRorConfig, RorProperties}
import tech.beshu.ror.es.{EsEnv, IndexJsonContentService}

object RawRorConfigLoadingAction {

  def load(env: EsEnv, indexJsonContentService: IndexJsonContentService)
          (implicit environmentConfig: EnvironmentConfig): Task[Either[LoadedRorConfig.Error, LoadedRorConfig[RawRorConfig]]] = {
    val compiler = ConfigLoadingInterpreter.create(
      new IndexConfigManager(indexJsonContentService, environmentConfig.propertiesProvider),
      RorProperties.rorIndexSettingLoadingDelay(environmentConfig.propertiesProvider)
    )
    (for {
      esConfig <- EitherT(ConfigLoading.loadEsConfig(env))
      loadedConfig <- EitherT(LoadRawRorConfig.load(env, esConfig, esConfig.rorIndex.index))
    } yield loadedConfig).value.foldMap(compiler)
  }

}
