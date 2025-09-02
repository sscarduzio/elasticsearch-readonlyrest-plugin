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
//package tech.beshu.ror.configuration.manager
//
//import cats.data.EitherT
//import monix.eval.Task
//import org.apache.logging.log4j.scala.Logging
//import tech.beshu.ror.configuration.index.IndexSettingsManager.LoadingIndexSettingsError
//import tech.beshu.ror.configuration.index.{IndexJsonContentServiceBasedIndexTestSettingsManager, IndexSettingsManager}
//import tech.beshu.ror.configuration.loader.RorSettingsLoader
//import tech.beshu.ror.configuration.loader.RorSettingsLoader.Error.{ParsingError, SpecializedError}
//import tech.beshu.ror.configuration.manager.InIndexSettingsManager.{LoadingFromIndexError, SavingIndexSettingsError}
//import tech.beshu.ror.configuration.{EsConfigBasedRorSettings, RawRorSettingsYamlParser, TestRorSettings}
//import tech.beshu.ror.es.IndexJsonContentService
//import tech.beshu.ror.implicits.*
//import tech.beshu.ror.utils.ScalaOps.LoggerOps
//
//import scala.language.postfixOps
//
//class RorTestSettingsManager private(indexSettingsManager: IndexSettingsManager[TestRorSettings])
//  extends InIndexSettingsManager[TestRorSettings]
//    with Logging {
//
//  override def loadFromIndex(): Task[Either[LoadingFromIndexError, TestRorSettings]] = {
//    val settingsIndex = indexSettingsManager.settingsIndex
//    val result = for {
//      _ <- lift(logger.info(s"Loading ReadonlyREST test settings from index (${settingsIndex.index.show}) ..."))
//      settings <- EitherT(indexSettingsManager.load())
//        .leftMap(convertIndexError)
//        .biSemiflatTap(
//          {
//            case LoadingFromIndexError.IndexParsingError(message) =>
//              logger.dError(s"Loading ReadonlyREST test settings from index failed: ${message.show}")
//            case LoadingFromIndexError.IndexUnknownStructure =>
//              logger.dInfo("Loading ReadonlyREST test settings from index failed: index content malformed")
//            case LoadingFromIndexError.IndexNotExist =>
//              logger.dInfo("Loading ReadonlyREST test settings from index failed: cannot find index")
//          },
//          {
//            case TestRorSettings.Present(rawConfig, _, _) =>
//              logger.dDebug(s"Loaded ReadonlyREST test settings from index: ${rawConfig.raw.show}")
//            case TestRorSettings.NotSet =>
//              logger.dDebug("There was no ReadonlyREST test settings in the index. Test settings engine will be not initialized.")
//          }
//        )
//    } yield settings
//    result.value
//  }
//
//  override def saveToIndex(settings: TestRorSettings): Task[Either[SavingIndexSettingsError, Unit]] = {
//    EitherT(indexSettingsManager.save(settings))
//      .leftMap {
//        case IndexSettingsManager.SavingIndexSettingsError.CannotSaveSettings => InIndexSettingsManager.SavingIndexSettingsError.CannotSaveSettings
//      }
//      .value
//  }
//
//  private def convertIndexError(error: RorSettingsLoader.Error[LoadingIndexSettingsError]): LoadingFromIndexError =
//    error match {
//      case ParsingError(error) => LoadingFromIndexError.IndexParsingError(error.show)
//      case SpecializedError(LoadingIndexSettingsError.IndexNotExist) => LoadingFromIndexError.IndexNotExist
//      case SpecializedError(LoadingIndexSettingsError.UnknownStructureOfIndexDocument) => LoadingFromIndexError.IndexUnknownStructure
//    }
//
//  private def lift[A](value: => A) = EitherT(Task.delay(Right(value)))
//}
//object RorTestSettingsManager {
//
//  def create(esConfigBasedRorSettings: EsConfigBasedRorSettings,
//             indexJsonContentService: IndexJsonContentService): RorTestSettingsManager = {
//    new RorTestSettingsManager(
//      new IndexJsonContentServiceBasedIndexTestSettingsManager(
//        settingsIndex = esConfigBasedRorSettings.rorSettingsIndex,
//        indexJsonContentService = indexJsonContentService,
//        rorSettingsYamlParser = RawRorSettingsYamlParser(esConfigBasedRorSettings.loadingRorCoreStrategy.rorSettingsMaxSize)
//      )
//    )
//  }
//}

// todo: remove