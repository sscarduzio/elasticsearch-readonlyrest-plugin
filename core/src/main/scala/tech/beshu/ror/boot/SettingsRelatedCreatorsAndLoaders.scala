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
package tech.beshu.ror.boot

import tech.beshu.ror.api.{MainSettingsApi, TestSettingsApi}
import tech.beshu.ror.boot.engines.{MainSettingsBasedReloadableEngine, TestSettingsBasedReloadableEngine}
import tech.beshu.ror.es.IndexDocumentManager
import tech.beshu.ror.settings.es.{EsConfigBasedRorSettings, LoadingRorCoreStrategySettings}
import tech.beshu.ror.settings.ror.RawRorSettingsYamlParser
import tech.beshu.ror.settings.ror.loader.{ConfigurableRetryStrategy, ForceLoadRorSettingsFromFileLoader, RetryableIndexSourceWithFileSourceFallbackRorSettingsLoader, StartingRorSettingsLoader}
import tech.beshu.ror.settings.ror.source.{MainSettingsFileSource, MainSettingsIndexSource, TestSettingsIndexSource}

final class SettingsRelatedCreatorsAndLoaders private(val startingRorSettingsLoader: StartingRorSettingsLoader,
                                                      val creators: SettingsRelatedCreators)

object SettingsRelatedCreatorsAndLoaders {

  def create(esConfigBasedRorSettings: EsConfigBasedRorSettings,
             indexDocumentManager: IndexDocumentManager): SettingsRelatedCreatorsAndLoaders = {
    val settingsIndex = esConfigBasedRorSettings.settingsSource.settingsIndex
    val settingsFile = esConfigBasedRorSettings.settingsSource.settingsFile
    val settingsMaxSize = esConfigBasedRorSettings.settingsSource.settingsMaxSize
    val settingsYamlParser = new RawRorSettingsYamlParser(settingsMaxSize)
    val mainSettingsIndexSource = MainSettingsIndexSource.create(indexDocumentManager, settingsIndex, settingsYamlParser)
    val mainSettingsFileSource = MainSettingsFileSource.create(settingsFile, settingsYamlParser)
    val testSettingsIndexSource = TestSettingsIndexSource.create(indexDocumentManager, settingsIndex, settingsYamlParser)
    val startingSettingsLoader = esConfigBasedRorSettings.loadingRorCoreStrategy match {
      case s@LoadingRorCoreStrategySettings.ForceLoadingFromFile$Settings =>
        new ForceLoadRorSettingsFromFileLoader(mainSettingsFileSource)
      case s@LoadingRorCoreStrategySettings.LoadFromIndexWithFileFallback(retryStrategySettings, _) =>
        new RetryableIndexSourceWithFileSourceFallbackRorSettingsLoader(
          mainSettingsIndexSource,
          new ConfigurableRetryStrategy(retryStrategySettings),
          mainSettingsFileSource,
          testSettingsIndexSource
        )
    }
    new SettingsRelatedCreatorsAndLoaders(
      startingSettingsLoader,
      new SettingsRelatedCreators(
        new MainSettingsBasedReloadableEngine.Creator(mainSettingsIndexSource),
        new MainSettingsApi.Creator(settingsYamlParser, mainSettingsIndexSource, mainSettingsFileSource),
        new TestSettingsBasedReloadableEngine.Creator(testSettingsIndexSource),
        new TestSettingsApi.Creator(settingsYamlParser)
      )
    )
  }
}

final class SettingsRelatedCreators(val mainSettingsBasedReloadableEngineCreator: MainSettingsBasedReloadableEngine.Creator,
                                    val mainSettingsApiCreator: MainSettingsApi.Creator,
                                    val testSettingsBasedReloadableEngineCreator: TestSettingsBasedReloadableEngine.Creator,
                                    val testSettingsApiCreator: TestSettingsApi.Creator)
