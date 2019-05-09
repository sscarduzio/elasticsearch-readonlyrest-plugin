package tech.beshu.ror.integration.utils


import java.io.File

import com.dimafeng.testcontainers.Container
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.schedulers.CanBlock.permit
import org.junit.runner.Description
import org.testcontainers.containers.Network
import tech.beshu.ror.utils.containers.ContainerUtils
import tech.beshu.ror.utils.containers.exceptions.ContainerCreationException

import scala.language.existentials

class ReadonlyRestEsClusterContainer private(val containers: Seq[Task[ReadonlyRestEsContainer]])
  extends Container {

  private var startedContainers: Seq[ReadonlyRestEsContainer] = Seq.empty

  override def starting()(implicit description: Description): Unit = {
    Task
      .gather(containers)
      .memoizeOnSuccess
      .flatMap { started =>
        startedContainers = started
        Task.gather(started.map(s => Task(s).map(_.starting()(description))))
      }
      .runSyncUnsafe()
  }

  override def finished()(implicit description: Description): Unit =
    startedContainers.foreach(_.finished()(description))

  override def succeeded()(implicit description: Description): Unit =
    startedContainers.foreach(_.succeeded()(description))

  override def failed(e: Throwable)(implicit description: Description): Unit =
    startedContainers.foreach(_.failed(e)(description))

}

object ReadonlyRestEsClusterContainer {

  def create(rorConfigFileName: String): ReadonlyRestEsClusterContainer =
    create(ContainerUtils.getResourceFile(rorConfigFileName))

  def create(rorConfigFile: File): ReadonlyRestEsClusterContainer = {
    val project = RorPluginGradleProject.fromSystemProperty
    val rorPluginFile: File = project.assemble.getOrElse(throw new ContainerCreationException("Plugin file assembly failed"))
    val network = Network.builder().id("test").build()
    new ReadonlyRestEsClusterContainer(Seq(
      Task(ReadonlyRestEsContainer.create("ROR1", "ROR1" :: "ROR2" :: Nil, project.getESVersion, rorPluginFile, rorConfigFile, network)),
      Task(ReadonlyRestEsContainer.create("ROR2", "ROR1" :: "ROR2" :: Nil, project.getESVersion, rorPluginFile, rorConfigFile, network))
    ))
  }
}
