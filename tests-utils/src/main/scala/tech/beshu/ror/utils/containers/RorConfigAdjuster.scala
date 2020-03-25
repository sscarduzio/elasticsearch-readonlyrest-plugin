package tech.beshu.ror.utils.containers

import better.files.File
import tech.beshu.ror.utils.containers.ContainerOps._
import tech.beshu.ror.utils.containers.EsClusterContainer.{StartedClusterDependencies, StartedDependency}
object RorConfigAdjuster {

  private val hostPlaceholder = "HOST"
  private val portPlaceholder = "PORT"

  sealed trait Mode
  object Mode {
    case object Plugin extends Mode
    case object Proxy extends Mode
  }

  final case class Replacement(host: String, port: Int)

  def adjustUsingDependencies(source: File,
                              startedDependencies: StartedClusterDependencies,
                              mode: Mode): File = {
    val configWithResolvedDependencies = startedDependencies.values
        .foldLeft(source.contentAsString)(replacePlaceholder(mode))

    createTempFile.overwrite(configWithResolvedDependencies)
  }

  private def replacePlaceholder(mode: Mode)(fileContent: String,
                                             dependency: StartedDependency): String = {
    val replacement = resolveReplacementForGivenMode(mode, dependency)
    fileContent
      .replaceAll(s"\\{${dependency.name}_$hostPlaceholder\\}", replacement.host)
      .replaceAll(s"\\{${dependency.name}_$portPlaceholder\\}", replacement.port.toString)
  }

  private def resolveReplacementForGivenMode(mode: Mode, dependency: StartedDependency): Replacement = mode match {
    case Mode.Plugin => Replacement(
      host = dependency.container.ipAddressFromFirstNetwork.getOrElse(throw new IllegalStateException("Could not extract ip address inside docker network")),
      port = dependency.originalPort
    )
    case Mode.Proxy => Replacement(
      host = dependency.container.containerIpAddress,
      port = dependency.container.mappedPort(dependency.originalPort)
    )
  }

  private def createTempFile = File.newTemporaryFile("tmp", ".tmp").deleteOnExit()
}
