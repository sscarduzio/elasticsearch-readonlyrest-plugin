package tech.beshu.ror.utils.containers.generic

import java.io.File

import cats.data.NonEmptyList
import monix.eval.Task
import tech.beshu.ror.utils.containers.ContainerUtils
import tech.beshu.ror.utils.containers.exceptions.ContainerCreationException
import tech.beshu.ror.utils.gradle.RorPluginGradleProject

trait EsClusterWithRorPluginProvider extends ClusterProvider {

  override def createLocalClusterContainer(clusterSettings: ClusterSettings) = {
    if (clusterSettings.numberOfInstances < 1) throw new IllegalArgumentException("Cluster should have at least one instance")

    val project = RorPluginGradleProject.fromSystemProperty
    val rorPluginFile: File = project.assemble.getOrElse(throw new ContainerCreationException("Plugin file assembly failed"))
    val esVersion = project.getESVersion
    val rorConfigFile = ContainerUtils.getResourceFile(clusterSettings.rorConfigFileName)
    val nodeNames = NonEmptyList.fromListUnsafe(Seq.iterate(1, clusterSettings.numberOfInstances)(_ + 1).toList
      .map(idx => s"${clusterSettings.name}_$idx"))

    new ReadonlyRestEsClusterContainer(
      nodeNames.map { name =>
        val containerConfig = ReadonlyRestEsContainer.Config(
          nodeName = name,
          nodes = nodeNames,
          esVersion = esVersion,
          rorPluginFile = rorPluginFile,
          rorConfigFile = rorConfigFile,
          configHotReloadingEnabled = clusterSettings.configHotReloadingEnabled,
          internodeSslEnabled = clusterSettings.internodeSslEnabled,
          xPackSupport = clusterSettings.xPackSupport)
        Task(ReadonlyRestEsContainer.create(containerConfig, clusterSettings.nodeDataInitializer))
      },
      clusterSettings.dependentServicesContainers,
      clusterSettings.clusterInitializer)
  }

  override def createRemoteClustersContainer(localClustersSettings: NonEmptyList[ClusterSettings],
                                             remoteClustersInitializer: RemoteClustersInitializer) = {
    val startedClusters = localClustersSettings.map(createLocalClusterContainer)
    new ReadonlyRestEsRemoteClustersContainer(startedClusters, remoteClustersInitializer)
  }
}
