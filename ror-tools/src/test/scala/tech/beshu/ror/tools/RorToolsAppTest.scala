/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.tools

import org.scalatest.BeforeAndAfter
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.must.Matchers.include
import org.scalatest.matchers.should.Matchers.{equal, should}
import tech.beshu.ror.tools.RorToolsApp.Result

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException, PrintStream}
import java.nio.file.Files.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path, Paths}
import java.util.UUID

class RorToolsAppTest extends AnyFunSuite with BeforeAndAfter {

  test("Patching is successful for ES installation that was not patched (with consent given in arg)") {
    withTestDirectory("test-es-not-patched") { testDirectory =>
      val (result, output) = captureResultAndOutput {
        RorToolsApp.handle(Array("patch", "--I-understand-implications-of-ES-patching", "yes", "--es-path", testDirectory))
      }
      result should equal(Result.Success)
      output should include(
        """Checking if Elasticsearch is patched ...
          |Creating backup ...
          |Patching ...
          |Elasticsearch is patched! ReadonlyREST is ready to use"""
          .stripMargin
      )
    }
  }

  test("Patching is successful for ES installation that was not patched (with consent given in interactive mode)") {
    withTestDirectory("test-es-not-patched") { testDirectory =>
      val (result, output) = captureResultAndOutputWithInteraction(
        RorToolsApp.handle(Array("patch", "--es-path", testDirectory)),
        response = "yes"
      )
      result should equal(Result.Success)
      output should include(
        """Elasticsearch needs to be patched to work with ReadonlyREST. You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.
          |Do you understand the implications of ES patching? (yes/no): Checking if Elasticsearch is patched ...
          |Creating backup ...
          |Patching ...
          |Elasticsearch is patched! ReadonlyREST is ready to use
          |""".stripMargin
      )
    }
  }

  test("Patching does not start when user declines to accept implications of patching (in arg)") {
    val (result, output) = captureResultAndOutput {
      RorToolsApp.handle(Array("patch", "--I-understand-implications-of-ES-patching", "no"))
    }
    result should equal(Result.Failure(1))
    output should equal(
      """You have to confirm, that You understand the implications of ES patching in order to perform it.
        |You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.
        |""".stripMargin
    )
  }

  test("Patching does not start when user declines to accept implications of patching (in interactive mode)") {
    val (result, output) = captureResultAndOutputWithInteraction(
      RorToolsApp.handle(Array("patch")),
      response = "no"
    )
    result should equal(Result.Failure(1))
    output should equal(
      """Elasticsearch needs to be patched to work with ReadonlyREST. You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.
        |Do you understand the implications of ES patching? (yes/no): You have to confirm, that You understand the implications of ES patching in order to perform it.
        |You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.
        |""".stripMargin
    )
  }

  test("Patching not started because of not existing directory") {
    val (result, output) = captureResultAndOutput {
      RorToolsApp.handle(Array("patch", "--I-understand-implications-of-ES-patching", "yes", "--es-path", "/wrong_directory"))
    }
    result should equal(Result.CommandNotParsed)
    output should include(
      """Error: Path [/wrong_directory] does not exist
        |Try --help for more information.""".stripMargin
    )
  }

  test("Patching fails because ES directory is in invalid state - with patching backup catalog") {
    withTestDirectory("test-es-partially-patched") { testDirectory =>
      val (result, output) = captureResultAndOutput {
        RorToolsApp.handle(Array("patch", "--I-understand-implications-of-ES-patching", "yes", "--es-path", testDirectory))
      }
      result should equal(Result.Failure(1))
      output should include(
        """Checking if Elasticsearch is patched ...
          |UNEXPECTED ERROR:
          |ES Corrupted! Something went wrong during patching/unpatching and the current state of ES installation is corrupted.
          |To recover from this state, please uninstall ReadonlyREST plugin and copy the corrupted files from ES binaries (https://www.elastic.co/downloads/elasticsearch):
          |""".stripMargin
      )
    }
  }

  private def withTestDirectory(name: String)(f: String => Unit): Unit = {
    val testDirectory = prepareTestEsDirectory(name)
    try {
      f(testDirectory)
    } finally {
      cleanDirectory(testDirectory)
    }
  }

  private def prepareTestEsDirectory(name: String): String = {
    val uuid = UUID.randomUUID().toString

    def copyDir(src: Path, dest: Path): Unit = {
      walk(src).forEach(file => copy(file, dest.resolve(src.relativize(file))))
    }

    val resource = getClass.getResource(s"/$name")
    val sourcePath = Path.of(resource.getPath)
    val destinationPath = Path.of(resource.getPath + s"-$uuid")
    copyDir(sourcePath, destinationPath)
    destinationPath.toString
  }

  def cleanDirectory(directory: String): Unit = {
    val dir = Paths.get(directory)
    Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor[java.nio.file.Path]() {
      override def visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult = {
        Files.delete(file)
        java.nio.file.FileVisitResult.CONTINUE
      }

      override def postVisitDirectory(dir: java.nio.file.Path, exc: IOException): java.nio.file.FileVisitResult = {
        Files.delete(dir)
        java.nio.file.FileVisitResult.CONTINUE
      }
    })
  }

  def captureResultAndOutput(block: => Result): (Result, String) = {
    val stream = new ByteArrayOutputStream()
    val printStream = new PrintStream(stream)
    val result = Console.withErr(printStream)(Console.withOut(printStream)(block))
    (result, stream.toString)
  }

  def captureResultAndOutputWithInteraction(block: => Result, response: String): (Result, String) = {
    val outStream = new ByteArrayOutputStream()
    val printStream = new PrintStream(outStream)
    val inputStream = new ByteArrayInputStream(response.getBytes)
    val result = Console.withErr(printStream)(Console.withIn(inputStream)(Console.withOut(printStream)(block)))
    (result, outStream.toString)
  }
}
