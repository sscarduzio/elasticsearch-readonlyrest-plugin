package tech.beshu.ror.utils.containers.generic

import better.files.File
import tech.beshu.ror.utils.containers.generic.ContainerOps._
import tech.beshu.ror.utils.containers.generic.EsClusterContainer.{StartedClusterDependencies, StartedDependency}

object FileAdjuster {

  private val hostPlaceholder = "HOST"
  private val portPlaceholder = "PORT"

  sealed trait Mode
  object Mode {
    case object Plugin extends Mode
    case object Proxy extends Mode
  }

  final case class Replacement(host: String, port: String)

  def adjust(source: File, startedDependencies: StartedClusterDependencies, mode: Mode): File = {
    val destination = createTempFile
    val lines = source.lineIterator
      .map { currentLine =>
        startedDependencies.values.foldLeft(currentLine) {
          case (line, dep) => replacePlaceholderInLine(line, dep, mode)
        }
      }
    destination.appendLines(lines.toSeq: _*)
  }

  private def replacePlaceholderInLine(line: String,
                                       dependency: StartedDependency,
                                       mode: Mode): String = {
    val replacement = resolveReplacementForGivenMode(mode, dependency)
    line
      .replaceAll(s"\\{${dependency.name}_$hostPlaceholder\\}", replacement.host)
      .replaceAll(s"\\{${dependency.name}_$portPlaceholder\\}", replacement.port)
  }

  private def resolveReplacementForGivenMode(mode: Mode, dependency: StartedDependency): Replacement = mode match {
    case Mode.Plugin => Replacement(
      host = dependency.container.bridgeIpAddress.getOrElse(throw new IllegalStateException("Could not extract bridge ip address")),
      port = dependency.originalPort.toString
    )
    case Mode.Proxy => Replacement(
      host = dependency.container.containerIpAddress,
      port = dependency.container.mappedPort(dependency.originalPort).toString
    )
  }

  private def createTempFile: File = File.newTemporaryFile("tmp", ".tmp").deleteOnExit()
}
