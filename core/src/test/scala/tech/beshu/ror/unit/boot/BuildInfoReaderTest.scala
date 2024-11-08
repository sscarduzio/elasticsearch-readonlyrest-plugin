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
package tech.beshu.ror.unit.boot

import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.buildinfo.BuildInfoReader

class BuildInfoReaderTest extends AnyWordSpec {

  import org.scalatest.matchers.should.Matchers.*

  "BuildInfoReader" should {
    "fail create from nonexistent file" in {
      val error = BuildInfoReader.create("/dontexist.properties").failed.get
      error.getMessage shouldEqual "file '/dontexist.properties' is expected to be present in plugin jar, but it wasn't found."
    }
    "create build info for default file" in {
      val buildInfo = BuildInfoReader.create("/stub-ror-build-info.properties").get
      buildInfo.esVersion shouldBe "es stub version"
      buildInfo.pluginVersion shouldBe "plugin stub version"
    }
  }
}
