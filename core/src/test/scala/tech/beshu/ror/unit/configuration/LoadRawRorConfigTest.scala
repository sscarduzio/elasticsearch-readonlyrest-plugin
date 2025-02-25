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
import tech.beshu.ror.configuration.loader.LoadedRorConfig.{FileConfig, ForcedFileConfig, IndexConfig}
import tech.beshu.ror.configuration.loader.{LoadRawRorConfig, LoadedRorConfig}
import tech.beshu.ror.configuration.{ConfigLoading, RawRorConfig}
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.utils.TestsUtils.{defaultEsVersionForTests, unsafeNes}

import java.nio.file.Paths
import scala.language.existentials

class LoadRawRorConfigTest extends AnyWordSpec with EitherValues{
  import LoadRawRorConfigTest.*
  "Free monad loader program" should {
    "load forced file" in {
      val steps = List(
        (ConfigLoading.LoadConfigAction.ForceLoadRorConfigFromFile(esEnv.configPath), Right(ForcedFileConfig(rawRorConfig))),
      )
      val compiler = IdCompiler.instance(steps)
      val program = LoadRawRorConfig.load(isLoadingFromFileForced = true, esEnv, rorConfigurationIndex, indexLoadingAttempts = 0)
      val result = program.foldMap(compiler)
      val ffc = result.asInstanceOf[Right[Nothing, ForcedFileConfig[RawRorConfig]]]
      ffc.value.value shouldEqual rawRorConfig
    }
    "load successfully from index" in {
      val steps = List(
        (ConfigLoading.LoadConfigAction.LoadRorConfigFromIndex(rorConfigurationIndex), Right(IndexConfig(rorConfigurationIndex, rawRorConfig))),
      )
      val compiler = IdCompiler.instance(steps)
      val program = LoadRawRorConfig.load(isLoadingFromFileForced = false, esEnv, rorConfigurationIndex, indexLoadingAttempts = 1)
      val result = program.foldMap(compiler)
      val ffc = result.asInstanceOf[Right[Nothing, IndexConfig[RawRorConfig]]]
      ffc.value.value shouldEqual rawRorConfig
    }
    "load successfully from index, after failure" in {
      val steps = List(
        (ConfigLoading.LoadConfigAction.LoadRorConfigFromIndex(rorConfigurationIndex), Left(LoadedRorConfig.IndexNotExist)),
        (ConfigLoading.LoadConfigAction.LoadRorConfigFromIndex(rorConfigurationIndex), Right(IndexConfig(rorConfigurationIndex, rawRorConfig))),
      )
      val compiler = IdCompiler.instance(steps)
      val program = LoadRawRorConfig.load(isLoadingFromFileForced = false, esEnv, rorConfigurationIndex, indexLoadingAttempts = 5)
      val result = program.foldMap(compiler)
      val ffc = result.asInstanceOf[Right[Nothing, IndexConfig[RawRorConfig]]]
      ffc.value.value shouldEqual rawRorConfig
    }
    "load from file when index not exist" in {
      val steps = List(
        (ConfigLoading.LoadConfigAction.LoadRorConfigFromIndex(rorConfigurationIndex), Left(LoadedRorConfig.IndexNotExist)),
        (ConfigLoading.LoadConfigAction.LoadRorConfigFromIndex(rorConfigurationIndex), Left(LoadedRorConfig.IndexNotExist)),
        (ConfigLoading.LoadConfigAction.LoadRorConfigFromIndex(rorConfigurationIndex), Left(LoadedRorConfig.IndexNotExist)),
        (ConfigLoading.LoadConfigAction.LoadRorConfigFromIndex(rorConfigurationIndex), Left(LoadedRorConfig.IndexNotExist)),
        (ConfigLoading.LoadConfigAction.LoadRorConfigFromIndex(rorConfigurationIndex), Left(LoadedRorConfig.IndexNotExist)),
        (ConfigLoading.LoadConfigAction.LoadRorConfigFromFile(esEnv.configPath), Right(FileConfig(rawRorConfig))),
      )
      val compiler = IdCompiler.instance(steps)
      val program = LoadRawRorConfig.load(isLoadingFromFileForced = false, esEnv, rorConfigurationIndex, indexLoadingAttempts = 5)
      val result = program.foldMap(compiler)
      result.toOption.get shouldBe FileConfig(rawRorConfig)
    }
    "unknown index structure fail loading from index immediately" in {
      val steps = List(
        (ConfigLoading.LoadConfigAction.LoadRorConfigFromIndex(rorConfigurationIndex), Left(LoadedRorConfig.IndexUnknownStructure)),
      )
      val compiler = IdCompiler.instance(steps)
      val program = LoadRawRorConfig.load(isLoadingFromFileForced = false, esEnv, rorConfigurationIndex, indexLoadingAttempts = 5)
      val result = program.foldMap(compiler)
      result shouldBe a[Left[LoadedRorConfig.IndexUnknownStructure.type, _]]
    }
    "parse index error fail loading from index immediately" in {
      val steps = List(
        (ConfigLoading.LoadConfigAction.LoadRorConfigFromIndex(rorConfigurationIndex), Left(LoadedRorConfig.IndexParsingError("error"))),
      )
      val compiler = IdCompiler.instance(steps)
      val program = LoadRawRorConfig.load(isLoadingFromFileForced = false, esEnv, rorConfigurationIndex, indexLoadingAttempts = 5)
      val result = program.foldMap(compiler)
      result shouldBe a[Left[LoadedRorConfig.IndexParsingError, _]]
      result.left.value.asInstanceOf[LoadedRorConfig.IndexParsingError].message shouldBe "error"
    }
  }
}
object LoadRawRorConfigTest {

  private val esEnv = EsEnv(Paths.get("unused_file_path"), Paths.get("unused_file_path"), defaultEsVersionForTests)
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