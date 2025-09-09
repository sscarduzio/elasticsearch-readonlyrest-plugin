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
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.LoadingRorCoreStrategy
import tech.beshu.ror.configuration.{EsConfigBasedRorSettings, RawRorSettingsYamlParser}
import tech.beshu.ror.es.IndexDocumentManager
import tech.beshu.ror.settings.source.{MainSettingsFileSource, MainSettingsIndexSource, TestSettingsIndexSource}
import tech.beshu.ror.settings.loader.{ConfigurableRetryStrategy, ForceLoadRorSettingsFromFileLoader, RetryableIndexSourceWithFileSourceFallbackRorSettingsLoader, StartingRorSettingsLoader}

final class SettingsRelatedCreatorsAndLoaders private(val startingRorSettingsLoader: StartingRorSettingsLoader,
                                                      val creators: SettingsRelatedCreators)

final class SettingsRelatedCreators(val mainSettingsBasedReloadableEngineCreator: MainSettingsBasedReloadableEngine.Creator,
                                    val mainSettingsApiCreator: MainSettingsApi.Creator,
                                    val testSettingsBasedReloadableEngineCreator: TestSettingsBasedReloadableEngine.Creator,
                                    val testSettingsApiCreator: TestSettingsApi.Creator)

object SettingsRelatedCreatorsAndLoaders {

  def create(esConfigBasedRorSettings: EsConfigBasedRorSettings,
             indexDocumentManager: IndexDocumentManager): SettingsRelatedCreatorsAndLoaders = {
    val settingsYamlParser = new RawRorSettingsYamlParser(esConfigBasedRorSettings.settingsMaxSize)
    val mainSettingsIndexSource = MainSettingsIndexSource.create(
      indexDocumentManager, esConfigBasedRorSettings.settingsIndex, settingsYamlParser
    )
    val mainSettingsFileSource = MainSettingsFileSource.create(
      esConfigBasedRorSettings.settingsFile, settingsYamlParser
    )
    val testSettingsIndexSource = TestSettingsIndexSource.create(
      indexDocumentManager, esConfigBasedRorSettings.settingsIndex, settingsYamlParser
    )
    val startingSettingsLoader = esConfigBasedRorSettings.loadingRorCoreStrategy match {
      case s@LoadingRorCoreStrategy.ForceLoadingFromFile =>
        new ForceLoadRorSettingsFromFileLoader(mainSettingsFileSource)
      case s@LoadingRorCoreStrategy.LoadFromIndexWithFileFallback(params) =>
        new RetryableIndexSourceWithFileSourceFallbackRorSettingsLoader(
          mainSettingsIndexSource,
          new ConfigurableRetryStrategy(params),
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
