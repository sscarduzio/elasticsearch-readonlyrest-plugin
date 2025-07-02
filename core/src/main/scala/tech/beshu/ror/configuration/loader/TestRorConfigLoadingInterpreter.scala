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
package tech.beshu.ror.configuration.loader

import cats.data.EitherT
import cats.~>
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.configuration.RorProperties.LoadingDelay
import tech.beshu.ror.configuration.TestRorConfigLoading.LoadRorTestConfigAction
import tech.beshu.ror.configuration.index.{IndexConfigError, IndexTestConfigManager}
import tech.beshu.ror.configuration.loader.RorConfigLoader.Error.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.loader.LoadedTestRorConfig.*
import tech.beshu.ror.configuration.{TestRorConfig, TestRorConfigLoading}
import tech.beshu.ror.implicits.*

object TestRorConfigLoadingInterpreter extends Logging {

  def create(indexConfigManager: IndexTestConfigManager): LoadRorTestConfigAction ~> Task = new (LoadRorTestConfigAction ~> Task) {
    override def apply[A](fa: LoadRorTestConfigAction[A]): Task[A] = fa match {
      case TestRorConfigLoading.LoadRorTestConfigAction.LoadTestRorConfigFromIndex(settings) =>
        val rorConfigIndex = settings.rorConfigIndex
        logger.info(s"[CLUSTERWIDE SETTINGS] Loading ReadonlyREST test settings from index (${rorConfigIndex.index.show}) ...")
        loadFromIndex(indexConfigManager, rorConfigIndex, settings.loadingDelay)
          .map { testConfig =>
            testConfig match {
              case TestRorConfig.Present(rawConfig, _, _) =>
                logger.debug(s"[CLUSTERWIDE SETTINGS] Loaded raw test config from index: ${rawConfig.raw.show}")
              case TestRorConfig.NotSet =>
                logger.debug("[CLUSTERWIDE SETTINGS] There was no test settings in index. Test settings engine will be not initialized.")
            }
            testConfig
          }
          .bimap(convertIndexError, LoadedTestRorConfig.apply)
          .leftMap { error =>
            logIndexLoadingError(error)
            error
          }.value
    }
  }

  private def logIndexLoadingError(error: LoadedTestRorConfig.LoadingIndexError): Unit = {
    error match {
      case IndexParsingError(message) =>
        logger.error(s"Loading ReadonlyREST settings from index failed: ${message.show}")
      case LoadedTestRorConfig.IndexUnknownStructure =>
        logger.info("Loading ReadonlyREST test settings from index failed: index content malformed")
      case LoadedTestRorConfig.IndexNotExist =>
        logger.info("Loading ReadonlyREST test settings from index failed: cannot find index")
    }
  }

  private def loadFromIndex(indexConfigManager: IndexTestConfigManager,
                            index: RorConfigurationIndex,
                            loadingDelay: LoadingDelay) = {
    EitherT {
      indexConfigManager
        .load(index)
        .delayExecution(loadingDelay.value.value)
    }
  }

  private def convertIndexError(error: RorConfigLoader.Error[IndexConfigError]): LoadedTestRorConfig.LoadingIndexError =
    error match {
      case ParsingError(error) => LoadedTestRorConfig.IndexParsingError(error.show)
      case SpecializedError(IndexConfigError.IndexConfigNotExist) => LoadedTestRorConfig.IndexNotExist
      case SpecializedError(IndexConfigError.IndexConfigUnknownStructure) => LoadedTestRorConfig.IndexUnknownStructure
    }

}
