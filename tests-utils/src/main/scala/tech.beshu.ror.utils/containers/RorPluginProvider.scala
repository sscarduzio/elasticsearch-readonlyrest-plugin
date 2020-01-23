package tech.beshu.ror.utils.containers

import java.io.File

import cats.data.NonEmptyList
import monix.eval.Task
import tech.beshu.ror.utils.containers.ReadonlyRestEsClusterContainerGeneric.AdditionalClusterSettings
import tech.beshu.ror.utils.containers.exceptions.ContainerCreationException
import tech.beshu.ror.utils.gradle.RorPluginGradleProject

object RorPluginProvider {

  def createLocalClusterContainer(name: String,
                                  rorConfigFileName: String,
                                  clusterSettings: AdditionalClusterSettings = AdditionalClusterSettings()) = {
    if (clusterSettings.numberOfInstances < 1) throw new IllegalArgumentException("ES Cluster should have at least one instance")

    val project = RorPluginGradleProject.fromSystemProperty
    val rorPluginFile: File = project.assemble.getOrElse(throw new ContainerCreationException("Plugin file assembly failed"))
    val esVersion = project.getESVersion
    val rorConfigFile = ContainerUtils.getResourceFile(rorConfigFileName)
    val nodeNames = NonEmptyList.fromListUnsafe(Seq.iterate(1, clusterSettings.numberOfInstances)(_ + 1).toList.map(idx => s"${name}_$idx"))

    new ReadonlyRestEsClusterContainerGeneric(
      nodeNames.map { name =>
        val containerConfig = ReadonlyRestEsContainerGeneric.Config(
          nodeName = name,
          nodes = nodeNames,
          esVersion = esVersion,
          rorPluginFile = rorPluginFile,
          rorConfigFile = rorConfigFile,
          configHotReloadingEnabled = clusterSettings.configHotReloadingEnabled,
          internodeSslEnabled = clusterSettings.internodeSslEnabled,
          xPackSupport = clusterSettings.xPackSupport)
        Task(ReadonlyRestEsContainerGeneric.create(containerConfig, clusterSettings.nodeDataInitializer))
      },
      clusterSettings.dependentServicesContainers,
      clusterSettings.clusterInitializer)
  }

  def createRemoteClustersContainer(localClusters: NonEmptyList[ReadonlyRestEsClusterContainerGeneric.LocalClusterDef],
                                    remoteClustersInitializer: RemoteClustersInitializerGeneric) = {
    val startedClusters = localClusters
      .map { cluster =>
        createLocalClusterContainer(
          cluster.name,
          cluster.rorConfigFileName,
          AdditionalClusterSettings(nodeDataInitializer = cluster.nodeDataInitializer))
      }
    new ReadonlyRestEsRemoteClustersContainerGeneric(startedClusters, remoteClustersInitializer)
  }
}
