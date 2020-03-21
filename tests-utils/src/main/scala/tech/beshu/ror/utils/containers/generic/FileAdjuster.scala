package tech.beshu.ror.utils.containers.generic

import java.util.regex.Pattern

import better.files.File
import com.dimafeng.testcontainers.SingleContainer
import org.testcontainers.containers.GenericContainer
import tech.beshu.ror.utils.containers.generic.EsClusterContainer.{StartedClusterDependencies, StartedDependency}

object FileAdjuster {

  def createTempFile: File = File.newTemporaryFile("tmp", ".tmp").deleteOnExit()

  def adjust(source: File, dest: File, startedDependencies: StartedClusterDependencies): File = {
    val lines = source.lineIterator
      .map { currentLine =>
        val adjusted = startedDependencies.values.foldLeft(currentLine) {
          case (line, dep) => replace(line, dep)
        }
        adjusted
      }
    dest.appendLines(lines.toSeq: _*)
  }

  def replace(line: String, dependency: StartedDependency): String = {
    val pattern = Pattern.compile(s"\\{${dependency.name}\\}").pattern
    val ipAddress = getIpAddress(dependency.container).getOrElse("lol")
    line.replaceAll(pattern, ipAddress)
  }

  def getIpAddress(container: SingleContainer[GenericContainer[_]]): Option[String] = {
    Option(container.containerInfo)
      .map(_.getNetworkSettings
        .getNetworks
        .get("bridge")
        .getIpAddress
      )
  }
}
