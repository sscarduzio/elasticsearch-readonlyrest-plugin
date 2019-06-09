package tech.beshu.ror.unit.boot

import java.time.Clock

import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.factory.CoreFactory
import tech.beshu.ror.boot.ReadonlyRest
import org.scalatest.Matchers._
import org.scalamock.scalatest.MockFactory
import tech.beshu.ror.utils.{EnvVarsProvider, OsEnvVarsProvider}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import tech.beshu.ror.acl.SequentialAcl
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.logging.AclLoggingDecorator
import tech.beshu.ror.es.IndexJsonContentManager.CannotReachContentSource
import tech.beshu.ror.es.{AuditSink, IndexJsonContentManager}
import tech.beshu.ror.utils.TestsUtils.getResourcePath

class ReadonlyRestStartingTests extends WordSpec with Inside with MockFactory with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(100, Millis)))

  private val readonlyRestBoot: ReadonlyRest = new ReadonlyRest {
    override implicit protected val clock: Clock = Clock.systemUTC()
    override protected val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider
    override protected def coreFactory: CoreFactory = mock[CoreFactory]
  }

  "A ReadonlyREST core" should {
    "be loaded from file" when {
      "index is not available but file config is provided" in {
        val mockedIndexJsonContentManager = mock[IndexJsonContentManager]
        (mockedIndexJsonContentManager.sourceOf _)
          .expects(".readonlyrest", "settings", "1")
          .once()
          .returns(Task.now(Left(CannotReachContentSource)))

        val result = readonlyRestBoot
          .start(
            getResourcePath("/boot_tests/no_index_config_file_config_provided/"),
            mock[AuditSink],
            mockedIndexJsonContentManager
          )
          .runSyncUnsafe()

        inside(result) {
          case Right(instance) =>
            eventually {
              instance.engine.isDefined should be (true)
              val blockNames = instance.engine.get
                .acl.asInstanceOf[AclLoggingDecorator]
                .underlying.asInstanceOf[SequentialAcl]
                .blocks.map(_.name).toList
              blockNames should contain theSameElementsAs List(Block.Name("CONTAINER ADMIN"))
            }
        }
      }
      "file loading is forced in elasticsearch.yml" in {
        val result = readonlyRestBoot
          .start(
            getResourcePath("/boot_tests/forced_file_loading/"),
            mock[AuditSink],
            mock[IndexJsonContentManager]
          )
          .runSyncUnsafe()

        inside(result) {
          case Right(instance) =>
            instance.engine.isDefined should be(true)
            val blockNames = instance.engine.get
              .acl.asInstanceOf[AclLoggingDecorator]
              .underlying.asInstanceOf[SequentialAcl]
              .blocks.map(_.name).toList
            blockNames should contain theSameElementsAs List(Block.Name("CONTAINER ADMIN"))
        }
      }
    }
  }

}
