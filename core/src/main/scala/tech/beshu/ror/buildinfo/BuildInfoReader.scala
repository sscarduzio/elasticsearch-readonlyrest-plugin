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
package tech.beshu.ror.buildinfo

import cats.effect.Resource
import monix.eval.Task
import monix.execution.Scheduler
import tech.beshu.ror.boot.RorSchedulers
import tech.beshu.ror.implicits.*

import java.io.InputStream
import java.util.{Objects, Properties}
import scala.util.Try

final case class BuildInfo(esVersion: String, pluginVersion: String)
object BuildInfoReader {
  private val filename = "/ror-build-info.properties"

  def create(filename: String = filename): Try[BuildInfo] = Try {
    implicit val scheduler: Scheduler = RorSchedulers.blockingScheduler
    createBuildInfoTask(filename).runSyncUnsafe()
  }

  private def createBuildInfoTask(filename: String) = {
    for {
      props <- loadProperties(filename)
      esVersion <- getProperty(props, "es_version")
      pluginVersion <- getProperty(props, "plugin_version")
    } yield BuildInfo(esVersion, pluginVersion)
  }

  private def loadProperties(filename: String): Task[Properties] = {
    createResource(filename).flatMap {
      tryWithResources(_, loadProperties)
    }
  }

  private def tryWithResources[A <: AutoCloseable, B](closeable: A, use: A => B): Task[B] = {
      Resource.fromAutoCloseable(Task.pure(closeable))
        .use(c => Task.pure(use(c)))
  }

  private def createResource(filename: String) =
    requireNonNull(this.getClass.getResourceAsStream(filename), s"file '${filename.show}' is expected to be present in plugin jar, but it wasn't found.")

  private def loadProperties(inputStream: InputStream) = {
    val props = new Properties()
    props.load(inputStream)
    props
  }

  private def getProperty(props: Properties, propertyName: String) =
    requireNonNull(props.getProperty(propertyName), s"Property value '${propertyName.show}' have to be defined")

  private def requireNonNull[A](a: A, message: String): Task[A] = Task {
    Objects.requireNonNull(a, message)
  }
}

