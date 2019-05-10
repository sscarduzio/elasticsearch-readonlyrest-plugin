package tech.beshu.ror.integration.utils.containers

import java.io.File

import cats.data.NonEmptyList
import com.dimafeng.testcontainers.Container
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.schedulers.CanBlock.permit
import org.junit.runner.Description
import tech.beshu.ror.integration.utils.RorPluginGradleProject
import tech.beshu.ror.utils.containers.ContainerUtils
import tech.beshu.ror.utils.containers.exceptions.ContainerCreationException
import tech.beshu.ror.utils.httpclient.RestClient

import scala.language.existentials

object ReadonlyRestEsClusterContainer {

  def create(rorConfigFileName: String,
             numberOfInstances: Int = 2,
             nodeDataInitializer: ElasticsearchNodeDataInitializer = NoOpElasticsearchNodeDataInitializer,
             clusterInitializer: ReadonlyRestEsClusterInitializer = NoOpReadonlyRestEsClusterInitializer): ReadonlyRestEsClusterContainer =
    create(ContainerUtils.getResourceFile(rorConfigFileName), numberOfInstances, nodeDataInitializer, clusterInitializer)

  def create(rorConfigFile: File,
             numberOfInstances: Int,
             nodeDataInitializer: ElasticsearchNodeDataInitializer,
             clusterInitializer: ReadonlyRestEsClusterInitializer): ReadonlyRestEsClusterContainer = {
    if (numberOfInstances <= 1) throw new IllegalArgumentException("Cluster should have at least two instances")
    val project = RorPluginGradleProject.fromSystemProperty
    val rorPluginFile: File = project.assemble.getOrElse(throw new ContainerCreationException("Plugin file assembly failed"))
    val esVersion = project.getESVersion

    val nodeNames = NonEmptyList.fromListUnsafe(Seq.iterate(1, numberOfInstances)(_ + 1).toList.map(idx => s"ROR$idx"))
    new ReadonlyRestEsClusterContainer(
      nodeNames.map { name =>
        Task(ReadonlyRestEsContainer.create(name, nodeNames, esVersion, rorPluginFile, rorConfigFile, nodeDataInitializer))
      },
      clusterInitializer
    )
  }

}

class ReadonlyRestEsClusterContainer private(containers: NonEmptyList[Task[ReadonlyRestEsContainer]],
                                             clusterInitializer: ReadonlyRestEsClusterInitializer)
  extends Container {

  val nodesContainers: NonEmptyList[ReadonlyRestEsContainer] = {
    NonEmptyList.fromListUnsafe(Task.gather(containers.toList).runSyncUnsafe())
  }

  override def starting()(implicit description: Description): Unit = {
    Task.gather(nodesContainers.toList.map(s => Task(s.starting()(description)))).runSyncUnsafe()
    clusterInitializer.initialize(nodesContainers.head.adminClient, this)
  }

  override def finished()(implicit description: Description): Unit =
    nodesContainers.toList.foreach(_.finished()(description))

  override def succeeded()(implicit description: Description): Unit =
    nodesContainers.toList.foreach(_.succeeded()(description))

  override def failed(e: Throwable)(implicit description: Description): Unit =
    nodesContainers.toList.foreach(_.failed(e)(description))

}

trait ReadonlyRestEsClusterInitializer {
  def initialize(adminClient: RestClient, container: ReadonlyRestEsClusterContainer): Unit
}

object NoOpReadonlyRestEsClusterInitializer extends ReadonlyRestEsClusterInitializer {
  override def initialize(adminClient: RestClient, container: ReadonlyRestEsClusterContainer): Unit = ()
}