package tech.beshu.ror.utils.containers.generic

import java.util.regex.Pattern

import better.files.File
import org.testcontainers.containers.GenericContainer

object FileAdjuster {

  def createTempFile: File = File.newTemporaryFile("tmp", ".tmp").deleteOnExit()

  def adjust(source: File, dest: File, dependendencies: List[(String, GenericContainer[_])]): File = {
    val lines = source.lineIterator
      .map { currentLine =>
        val adjusted = dependendencies.foldLeft(currentLine) {
          case (line, dep) => replace(line, dep)
        }
        adjusted
      }
    dest.appendLines(lines.toSeq: _*)
  }

  def replace(line: String, dependency: (String, GenericContainer[_])): String = {
    val pattern = Pattern.compile(s"\\{${dependency._1}\\}").pattern
    val ipAddress = getIpAddress(dependency._2).getOrElse("lol")
    line.replaceAll(pattern, ipAddress)
  }

  def getIpAddress(container: GenericContainer[_]): Option[String] = {
    Option(container.getContainerInfo)
      .map(_.getNetworkSettings
        .getNetworks
        .get("bridge")
        .getIpAddress
      )
  }
}
