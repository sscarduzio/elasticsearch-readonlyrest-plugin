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
package tech.beshu.ror.unit.configuration

import cats.{Id, ~>}
import io.circe.Json
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorConfigurationIndex}
import tech.beshu.ror.configuration.ConfigLoading.LoadConfigAction
import tech.beshu.ror.configuration.ConfigLoading.LoadConfigAction.*
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.configuration.RorProperties.{LoadingAttemptsCount, LoadingAttemptsInterval, LoadingDelay}
import tech.beshu.ror.configuration.loader.LoadedRorConfig.{FileConfig, ForcedFileConfig, IndexConfig}
import tech.beshu.ror.configuration.loader.{LoadRawRorConfig, LoadedRorConfig}
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.utils.TestsUtils.{defaultEsVersionForTests, testEsNodeSettings, unsafeNes}

import java.nio.file.Paths
import scala.concurrent.duration.*
import scala.language.{existentials, postfixOps}

class LoadRawRorConfigTest extends AnyWordSpec with EitherValues{
  import LoadRawRorConfigTest.*
  "Free monad loader program" should {
    "load forced file" in {
      val steps = List(
        (ForceLoadRorConfigFromFile(esEnv.configPath), Right(ForcedFileConfig(rawRorConfig))),
      )
      val compiler = IdCompiler.instance(steps)
      val program = LoadRawRorConfig.loadFromFile(esEnv.configPath)
      val result = program.foldMap(compiler)
      val ffc = result.asInstanceOf[Right[Nothing, ForcedFileConfig[RawRorConfig]]]
      ffc.value.value shouldEqual rawRorConfig
    }
    "load successfully from index" in {
      val loadingDelay = LoadingDelay.unsafeFrom(2 seconds)
      val steps = List(
        (LoadRorConfigFromIndex(rorConfigurationIndex, loadingDelay.value), Right(IndexConfig(rorConfigurationIndex, rawRorConfig))),
      )
      val compiler = IdCompiler.instance(steps)
      val program = LoadRawRorConfig.loadFromIndexWithFileFallback(
        configurationIndex = rorConfigurationIndex,
        loadingDelay = loadingDelay,
        loadingAttemptsCount = LoadingAttemptsCount.unsafeFrom(1),
        loadingAttemptsInterval = LoadingAttemptsInterval.unsafeFrom(1 second),
        fallbackConfigFilePath = esEnv.configPath
      )
      val result = program.foldMap(compiler)
      val ffc = result.asInstanceOf[Right[Nothing, IndexConfig[RawRorConfig]]]
      ffc.value.value shouldEqual rawRorConfig
    }
    "load successfully from index, after failure" in {
      val loadingDelay = LoadingDelay.unsafeFrom(2 seconds)
      val loadingAttemptsInterval = LoadingAttemptsInterval.unsafeFrom(1 second)
      val steps = List(
        (LoadRorConfigFromIndex(rorConfigurationIndex, loadingDelay.value), Left(LoadedRorConfig.IndexNotExist)),
        (LoadRorConfigFromIndex(rorConfigurationIndex, loadingAttemptsInterval.value), Right(IndexConfig(rorConfigurationIndex, rawRorConfig))),
      )
      val compiler = IdCompiler.instance(steps)
      val program = LoadRawRorConfig.loadFromIndexWithFileFallback(
        configurationIndex = rorConfigurationIndex,
        loadingDelay = loadingDelay,
        loadingAttemptsCount = LoadingAttemptsCount.unsafeFrom(5),
        loadingAttemptsInterval = loadingAttemptsInterval,
        fallbackConfigFilePath = esEnv.configPath
      )
      val result = program.foldMap(compiler)
      val ffc = result.asInstanceOf[Right[Nothing, IndexConfig[RawRorConfig]]]
      ffc.value.value shouldEqual rawRorConfig
    }
    "load from file when index not exist" in {
      val loadingDelay = LoadingDelay.unsafeFrom(2 seconds)
      val loadingAttemptsInterval = LoadingAttemptsInterval.unsafeFrom(1 second)
      val steps = List(
        (LoadRorConfigFromIndex(rorConfigurationIndex, loadingDelay.value), Left(LoadedRorConfig.IndexNotExist)),
        (LoadRorConfigFromIndex(rorConfigurationIndex, loadingAttemptsInterval.value), Left(LoadedRorConfig.IndexNotExist)),
        (LoadRorConfigFromIndex(rorConfigurationIndex, loadingAttemptsInterval.value), Left(LoadedRorConfig.IndexNotExist)),
        (LoadRorConfigFromIndex(rorConfigurationIndex, loadingAttemptsInterval.value), Left(LoadedRorConfig.IndexNotExist)),
        (LoadRorConfigFromIndex(rorConfigurationIndex, loadingAttemptsInterval.value), Left(LoadedRorConfig.IndexNotExist)),
        (LoadRorConfigFromFile(esEnv.configPath), Right(FileConfig(rawRorConfig))),
      )
      val compiler = IdCompiler.instance(steps)
      val program = LoadRawRorConfig.loadFromIndexWithFileFallback(
        configurationIndex = rorConfigurationIndex,
        loadingDelay = loadingDelay,
        loadingAttemptsCount = LoadingAttemptsCount.unsafeFrom(5),
        loadingAttemptsInterval = loadingAttemptsInterval,
        fallbackConfigFilePath = esEnv.configPath
      )
      val result = program.foldMap(compiler)
      result.toOption.get shouldBe FileConfig(rawRorConfig)
    }
    "unknown index structure fail loading from index immediately" in {
      val loadingDelay = LoadingDelay.unsafeFrom(2 seconds)
      val steps = List(
        (LoadRorConfigFromIndex(rorConfigurationIndex, loadingDelay.value), Left(LoadedRorConfig.IndexUnknownStructure)),
      )
      val compiler = IdCompiler.instance(steps)
      val program = LoadRawRorConfig.loadFromIndexWithFileFallback(
        configurationIndex = rorConfigurationIndex,
        loadingDelay = loadingDelay,
        loadingAttemptsCount = LoadingAttemptsCount.unsafeFrom(5),
        loadingAttemptsInterval = LoadingAttemptsInterval.unsafeFrom(1 second),
        fallbackConfigFilePath = esEnv.configPath
      )
      val result = program.foldMap(compiler)
      result shouldBe a[Left[LoadedRorConfig.IndexUnknownStructure.type, _]]
    }
    "parse index error fail loading from index immediately" in {
      val loadingDelay = LoadingDelay.unsafeFrom(2 seconds)
      val steps = List(
        (LoadRorConfigFromIndex(rorConfigurationIndex, loadingDelay.value), Left(LoadedRorConfig.IndexParsingError("error"))),
      )
      val compiler = IdCompiler.instance(steps)
      val program = LoadRawRorConfig.loadFromIndexWithFileFallback(
        configurationIndex = rorConfigurationIndex,
        loadingDelay = loadingDelay,
        loadingAttemptsCount = LoadingAttemptsCount.unsafeFrom(5),
        loadingAttemptsInterval = LoadingAttemptsInterval.unsafeFrom(1 second),
        fallbackConfigFilePath = esEnv.configPath
      )
      val result = program.foldMap(compiler)
      result shouldBe a[Left[LoadedRorConfig.IndexParsingError, _]]
      result.left.value.asInstanceOf[LoadedRorConfig.IndexParsingError].message shouldBe "error"
    }
  }
}
object LoadRawRorConfigTest {

  private val esEnv = EsEnv(Paths.get("unused_file_path"), Paths.get("unused_file_path"), defaultEsVersionForTests, testEsNodeSettings)
  private val rawRorConfig = RawRorConfig(Json.False, "forced file config")
  private val rorConfigurationIndex = RorConfigurationIndex(IndexName.Full("rorConfigurationIndex"))

}
object IdCompiler {
  def instance(mocksDef: List[(LoadConfigAction[_], _)]): LoadConfigAction ~> Id = new (LoadConfigAction ~> Id) {
    var mocks: List[(LoadConfigAction[_], _)] = mocksDef

    override def apply[A](fa: LoadConfigAction[A]): Id[A] = {
      val (f1, r) = mocks.head
      mocks = mocks.tail
      assert(f1 == fa)
      r.asInstanceOf[Id[A]]
    }
  }
}