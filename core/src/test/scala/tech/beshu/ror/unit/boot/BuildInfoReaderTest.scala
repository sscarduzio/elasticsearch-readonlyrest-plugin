package tech.beshu.ror.unit.boot

import monix.execution.Scheduler.Implicits.global
import org.scalatest.WordSpec
import tech.beshu.ror.es.BuildInfoReader

class BuildInfoReaderTest extends WordSpec {

  import org.scalatest.Matchers._

  "BuildInfoReader" should {
    "fail create from nonexistent file" in {
      val error = BuildInfoReader.create("/dontexist.properties").failed.runSyncUnsafe()
      error.getMessage shouldEqual "file '/dontexist.properties' is expected to be present in plugin jar, but it wasn't found."
    }
    "create build info for default file" in {
      val buildInfo = BuildInfoReader.create("/stub-ror-build-info.properties").runSyncUnsafe()
      buildInfo.esVersion shouldBe "es stub version"
      buildInfo.pluginVersion shouldBe "plugin stub version"
    }
  }
}
