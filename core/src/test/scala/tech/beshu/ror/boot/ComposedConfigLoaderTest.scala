package tech.beshu.ror.boot

import better.files.File
import cats.implicits._
import io.circe.Json
import monix.eval.Task
import monix.execution.schedulers.TestScheduler
import org.scalactic.source.Position
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.boot.ComposedConfigLoader.LoadedConfig
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.EsConfig.RorEsLevelSettings
import tech.beshu.ror.configuration.FileConfigLoader.FileConfigError
import tech.beshu.ror.configuration.IndexConfigManager.IndexConfigError
import tech.beshu.ror.configuration.{ConfigLoader, RawRorConfig}

import concurrent.duration._
import language.postfixOps

class ComposedConfigLoaderTest extends WordSpec {
  implicit val scheduler = TestScheduler()

  private val parsingError = ConfigLoader.ConfigLoaderError.ParsingError(RawRorConfig.ParsingRorConfigError.NoRorSection).asLeft
  private val emptyRawConfig = RawRorConfig(Json.fromString(""), "")
  private val tempFile = File.newTemporaryFile()

  private val configFileNotExist = specializedError(FileConfigError.FileNotExist(tempFile))
  private val configFileMalformed = parsingError
  private val configFromFile = emptyRawConfig.asRight

  private val configIndexNotExist = specializedError(IndexConfigError.IndexConfigNotExist)
  private val configIndexUnknownStructure = specializedError(IndexConfigError.IndexConfigUnknownStructure)
  private val configIndexMalformed = parsingError
  private val configFromIndex = emptyRawConfig.asRight

  private val fileNotExistResult = ComposedConfigLoader.File.FileNotExist(tempFile).asLeft
  private val noRorSectionInFileResult = ComposedConfigLoader.File.ParsingError(RawRorConfig.ParsingRorConfigError.NoRorSection).asLeft
  private val noRorSectionInIndexResult = ComposedConfigLoader.Index.ParsingError(RawRorConfig.ParsingRorConfigError.NoRorSection).asLeft
  private val forceLoadedFromFileResult = ComposedConfigLoader.ForcedFile(emptyRawConfig).asRight
  private val loadedFromIndexResult = ComposedConfigLoader.Index(emptyRawConfig).asRight

  private val retryingLoadFromIndexDuration:FiniteDuration = 25 second

  "ComposedConfigLoader" when {
    "loading from file is forced" when {
      val esConfig = RorEsLevelSettings(forceLoadRorFromFile = true)
      "file config is can't be found" should {
        val fileResult = configFileNotExist
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexNotExist,
          expectedResult = fileNotExistResult,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexUnknownStructure,
          expectedResult = fileNotExistResult,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexMalformed,
          expectedResult = fileNotExistResult,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configFromIndex,
          expectedResult = fileNotExistResult,
        )
      }
      "file config is malformed" should {
        val fileResult = configFileMalformed
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexNotExist,
          expectedResult = noRorSectionInFileResult,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexUnknownStructure,
          expectedResult = noRorSectionInFileResult,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexMalformed,
          expectedResult = noRorSectionInFileResult,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configFromIndex,
          expectedResult = noRorSectionInFileResult,
        )
      }
      "file config is loaded" should {
        val fileResult = configFromFile
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexNotExist,
          expectedResult = forceLoadedFromFileResult,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexUnknownStructure,
          expectedResult = forceLoadedFromFileResult,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexMalformed,
          expectedResult = forceLoadedFromFileResult,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configFromIndex,
          expectedResult = forceLoadedFromFileResult,
        )
      }
    }
    "loading from file is not forced" when {
      val esConfig = RorEsLevelSettings(forceLoadRorFromFile = false)
      "file config is can't be found" should {
        val fileResult = configFileNotExist
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexNotExist,
          expectedResult = fileNotExistResult,
          configResolveTime = retryingLoadFromIndexDuration,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexUnknownStructure,
          expectedResult = fileNotExistResult,
          configResolveTime = retryingLoadFromIndexDuration,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexMalformed,
          expectedResult = noRorSectionInIndexResult,
          configResolveTime = retryingLoadFromIndexDuration,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configFromIndex,
          expectedResult = loadedFromIndexResult,
          configResolveTime = retryingLoadFromIndexDuration,
        )
      }
      "file config is malformed" should {
        val fileResult = configFileMalformed
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexNotExist,
          expectedResult = noRorSectionInFileResult,
          configResolveTime = retryingLoadFromIndexDuration,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexUnknownStructure,
          expectedResult = noRorSectionInFileResult,
          configResolveTime = retryingLoadFromIndexDuration,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexMalformed,
          expectedResult = noRorSectionInIndexResult,
          configResolveTime = retryingLoadFromIndexDuration,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configFromIndex,
          expectedResult = loadedFromIndexResult,
          configResolveTime = retryingLoadFromIndexDuration,
        )
      }
      "file config is correct" should {
        val fileResult = configFromFile
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexNotExist,
          expectedResult = ComposedConfigLoader.FileRecoveredIndex(emptyRawConfig, ComposedConfigLoader.Index.IndexConfigNotExist).asRight,
          configResolveTime = retryingLoadFromIndexDuration,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexUnknownStructure,
          expectedResult = ComposedConfigLoader.FileRecoveredIndex(emptyRawConfig, ComposedConfigLoader.Index.IndexConfigUnknownStructure).asRight,
          configResolveTime = retryingLoadFromIndexDuration,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configIndexMalformed,
          expectedResult = noRorSectionInIndexResult,
          configResolveTime = retryingLoadFromIndexDuration,
        )
        testLoader(esConfig = esConfig,
          fileResult = fileResult,
          indexResult = configFromIndex,
          expectedResult = loadedFromIndexResult,
          configResolveTime = retryingLoadFromIndexDuration,
        )
      }
    }

  }

  val configResolveTime:FiniteDuration = 5 seconds
  private def testLoader(esConfig: RorEsLevelSettings,
                         fileResult: Either[ConfigLoaderError[FileConfigError], RawRorConfig],
                         indexResult: Either[ConfigLoaderError[IndexConfigError], RawRorConfig],
                         expectedResult: Either[LoadedConfig.Error, LoadedConfig],
                         configResolveTime:FiniteDuration = 0 second)
                        (implicit position: Position): Unit = {
    s"test for $fileResult, $indexResult, $esConfig expects $expectedResult" in {
      val result = new ComposedConfigLoader(pureLoader(fileResult), pureLoader(indexResult), esConfig).loadConfig()
      val future = result.runToFuture
      scheduler.tick(configResolveTime)
      future.value.get.get shouldEqual expectedResult
    }
  }

  private def pureLoader[E](result: Either[ConfigLoaderError[E], RawRorConfig]): ConfigLoader[E] = () => Task.pure(result)

  private def specializedError[E](error: E) =
    Left(ConfigLoader.ConfigLoaderError.SpecializedError(error))

}
