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
package tech.beshu.ror.utils.gradle

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

import java.io.{File => JFile}
import java.nio.file.Paths

import better.files._
import monix.execution.atomic.Atomic
import org.gradle.tooling.GradleConnector

import scala.util.Try

final class LastEsModuleCache[A]() {
  private val lastElem: Atomic[Option[(String, A)]] = Atomic(Option.empty[(String, A)])

  def get(moduleName: String, createNew: => A): A = {
    lastElem.transformAndExtract {
      case t@Some((`moduleName`, project)) => (project, t)
      case _ =>
        val elem = createNew
        (elem, Some((moduleName, elem)))
    }
  }
}
object RorPluginGradleProject {
  private val lastProjectCache = new LastEsModuleCache[RorPluginGradleProject]

  def apply(moduleName: String): RorPluginGradleProject = {
    lastProjectCache.get(moduleName, new RorPluginGradleProject(moduleName))
  }

  def fromSystemProperty: RorPluginGradleProject =
    Option(System.getProperty("esModule"))
      .map(RorPluginGradleProject(_))
      .getOrElse(throw new IllegalStateException("No 'esModule' system property set"))

  def getRootProject: JFile = {
    Option(System.getProperty("project.dir"))
      .map(projectDir => Paths.get(projectDir).toFile)
      .getOrElse(new JFile("."))
  }

  def availableEsModules: List[String] =
    RorPluginGradleProject
      .getRootProject.toScala
      .children
      .filter { f => f.isDirectory }
      .map(_.name)
      .filter(_.matches("^es\\d\\dx$"))
      .toList
}

class RorPluginGradleProject private(val moduleName: String) {
  private val project = esProject(moduleName)
  private val esProjectProperties =
    GradleProperties
      .create(project)
      .getOrElse(throw new IllegalStateException("cannot load '" + moduleName + "' project gradle.properties file"))
  private val rootProjectProperties =
    GradleProperties
      .create(RorPluginGradleProject.getRootProject)
      .getOrElse(throw new IllegalStateException("cannot load root project gradle.properties file"))

  lazy val assemble: Option[JFile] = {
    runTask(moduleName + ":ror")
    val plugin = new JFile(project, "build/distributions/" + pluginName)
    if (!plugin.exists) None
    else Some(plugin)
  }

  def getESVersion: String = esProjectProperties.getProperty("esVersion")

  private def esProject(esProjectName: String) = new JFile(RorPluginGradleProject.getRootProject, esProjectName)

  private def pluginName =
    s"${rootProjectProperties.getProperty("pluginName")}-${rootProjectProperties.getProperty("pluginVersion")}_es$getESVersion.zip"

  private def runTask(task: String): Unit = {
    val connector = GradleConnector.newConnector.forProjectDirectory(RorPluginGradleProject.getRootProject)
    val connect = Try(connector.connect())
    val result = connect.map(_.newBuild().forTasks(task).run())
    connect.map(_.close())
    result.fold(throw _, _ => ())
  }
}

