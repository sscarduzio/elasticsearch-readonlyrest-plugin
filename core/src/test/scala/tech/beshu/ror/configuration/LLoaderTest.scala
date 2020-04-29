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
package tech.beshu.ror.configuration

import cats.{Id, ~>}
import io.circe.Json
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.configuration.LLoader.LoadA
import tech.beshu.ror.configuration.loader.LoadedConfig.{FileRecoveredConfig, ForcedFileConfig, IndexConfig}
import tech.beshu.ror.configuration.loader.Path

import scala.language.existentials

class LLoaderTest extends WordSpec {
  import LLoaderTest._
  "Free monad interpreter" should {
    "load forced file" in {
      val steps = List(
        (LLoader.ForceLoadFromFile(filePath), Right(ForcedFileConfig(rawRorConfig))),
      )
      val compiler = IdCompiler.instance(steps)
      val program = LLoader.program(isLoadingFromFileForced = true, filePath, indexName, indexLoadingAttempts = 0)
      val result = program.foldMap(compiler)
      val ffc = result.asInstanceOf[Right[Nothing, ForcedFileConfig[RawRorConfig]]]
      ffc.value.value shouldEqual rawRorConfig
    }
    "load successfully from index" in {
      val steps = List(
        (LLoader.LoadFromIndex(indexName), Right(IndexConfig(rawRorConfig))),
      )
      val compiler = IdCompiler.instance(steps)
      val program = LLoader.program(isLoadingFromFileForced = false, filePath, indexName, indexLoadingAttempts = 0)
      val result = program.foldMap(compiler)
      val ffc = result.asInstanceOf[Right[Nothing, IndexConfig[RawRorConfig]]]
      ffc.value.value shouldEqual rawRorConfig
    }
    "load successfully from index, after failure" in {
      val steps = List(
        (LLoader.LoadFromIndex(indexName), Left(FileRecoveredConfig.indexNotExist)),
        (LLoader.LoadFromIndex(indexName), Right(IndexConfig(rawRorConfig))),
      )
      val compiler = IdCompiler.instance(steps)
      val program = LLoader.program(isLoadingFromFileForced = false, filePath, indexName, indexLoadingAttempts = 5)
      val result = program.foldMap(compiler)
      val ffc = result.asInstanceOf[Right[Nothing, IndexConfig[RawRorConfig]]]
      ffc.value.value shouldEqual rawRorConfig
    }
    "fail loading from index, and fallback to file" in {
      val steps = List(
        (LLoader.LoadFromIndex(indexName), Left(FileRecoveredConfig.indexNotExist)),
        (LLoader.LoadFromIndex(indexName), Left(FileRecoveredConfig.indexNotExist)),
        (LLoader.LoadFromIndex(indexName), Left(FileRecoveredConfig.indexNotExist)),
        (LLoader.LoadFromIndex(indexName), Left(FileRecoveredConfig.indexNotExist)),
        (LLoader.LoadFromIndex(indexName), Left(FileRecoveredConfig.indexUnknownStructure)),
        (LLoader.RecoverIndexWithFile(filePath, FileRecoveredConfig.indexUnknownStructure), Right(FileRecoveredConfig(rawRorConfig, FileRecoveredConfig.indexUnknownStructure))),
      )
      val compiler = IdCompiler.instance(steps)
      val program = LLoader.program(isLoadingFromFileForced = false, filePath, indexName, indexLoadingAttempts = 5)
      val result = program.foldMap(compiler)
      val ffc = result.asInstanceOf[Right[Nothing, FileRecoveredConfig[RawRorConfig]]]
      ffc.value.value shouldEqual rawRorConfig
      ffc.value.cause shouldEqual FileRecoveredConfig.indexUnknownStructure
    }
  }
}
object LLoaderTest {
  import eu.timepit.refined.auto._
  private val filePath = Path("unused_file_path")
  private val rawRorConfig = RawRorConfig(Json.False, "forced file config")
  private val indexName = IndexName("indexName")

}
object IdCompiler {
  def instance(mocksDef: List[(LoadA[_], _)]): LoadA ~> Id = new (LoadA ~> Id) {
    var mocks: List[(LoadA[_], _)] = mocksDef

    override def apply[A](fa: LoadA[A]): Id[A] = {
      val (f1, r) = mocks.head
      mocks = mocks.tail
      assert(f1 == fa)
      r.asInstanceOf[Id[A]]
    }
  }
}