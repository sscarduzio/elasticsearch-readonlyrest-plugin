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
package tech.beshu.ror.settings.strategy

import monix.eval.Task
import tech.beshu.ror.boot.ReadonlyRest.StartingFailure
import tech.beshu.ror.configuration.{RawRorSettings, TestRorSettings}
import tech.beshu.ror.settings.source.ReadOnlySettingsSource.LoadingSettingsError
import tech.beshu.ror.settings.source.{FileSettingsSource, IndexSettingsSource}

class StartingRorSettingsLoadingStrategy(mainSettingsIndexSource: IndexSettingsSource[RawRorSettings],
                                         mainSettingsFileSource: FileSettingsSource[RawRorSettings],
                                         testSettingsIndexSource: IndexSettingsSource[TestRorSettings],
                                         /* todo: retry strategy*/) {

  def load(): Task[Either[StartingFailure, (RawRorSettings, TestRorSettings)]] = {
    mainSettingsIndexSource.toString
    mainSettingsFileSource.toString
    testSettingsIndexSource.toString
    ???
  }

//  private def toStartingFailure(error: LoadingError) = {
//    error match {
//      case Left(LoadingSettingsError.FormatError) =>
//        StartingFailure(???)
//      case Right(LoadingSettingsError.FormatError) =>
//        StartingFailure(???)
//      //      case Left(LoadingSettingsError.FileParsingError(message)) =>
//      //        StartingFailure(message)
//      //      case Left(LoadingFromFileError.FileNotExist(file)) =>
//      //        StartingFailure(s"Cannot find settings file: ${file.show}")
//      //      case Right(LoadingFromIndexError.IndexParsingError(message)) =>
//      //        StartingFailure(message)
//      //      case Right(LoadingFromIndexError.IndexUnknownStructure) =>
//      //        StartingFailure(s"Settings index is malformed")
//      //      case Right(LoadingFromIndexError.IndexNotExist) =>
//      //        StartingFailure(s"Settings index doesn't exist")
//    }
//  }
//
//  private def loadTestSettings(esConfig: EsConfigBasedRorSettings,
//                               indexSettingsSource: IndexSettingsSource[TestRorSettings]): EitherT[Task, StartingFailure, TestRorSettings] = {
//    esConfig.loadingRorCoreStrategy match {
//      case LoadingRorCoreStrategy.ForceLoadingFromFile(_) =>
//        EitherT.rightT[Task, StartingFailure](TestRorSettings.NotSet)
//      case LoadingRorCoreStrategy.LoadFromIndexWithFileFallback(parameters, _) =>
//        EitherT(new TestSettingsIndexOnlyLoadingStrategy(indexSettingsSource).load())
//          .leftFlatMap {
//            case LoadingSettingsError.FormatError => ???
//            // todo:
//            //          case LoadingFromIndexError.IndexParsingError(message) =>
//            //            logger.error(s"Loading ReadonlyREST test settings from index failed: ${message.show}. No test settings will be loaded.")
//            //            EitherT.rightT[Task, StartingFailure](notSetTestRorSettings)
//            //          case LoadingFromIndexError.IndexUnknownStructure =>
//            //            logger.error("Loading ReadonlyREST test settings from index failed: index content malformed. No test settings will be loaded.")
//            //            EitherT.rightT[Task, StartingFailure](notSetTestRorSettings)
//            //          case LoadingFromIndexError.IndexNotExist =>
//            //            logger.info("Loading ReadonlyREST test settings from index failed: cannot find index. No test settings will be loaded.")
//            //            EitherT.rightT[Task, StartingFailure](notSetTestRorSettings)
//          }
//    }
//  }

}
object StartingRorSettingsLoadingStrategy {

  type LoadingError = Either[LoadingSettingsError, LoadingSettingsError]

}