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

import java.io.File
import java.nio.file.Paths

import org.gradle.tooling.GradleConnector

import scala.compat.java8.OptionConverters._
import scala.util.Try

object RorPluginGradleProject {
  def fromSystemProperty: RorPluginGradleProject =
    Option(System.getProperty("esModule"))
      .map(new RorPluginGradleProject(_))
      .getOrElse(throw new IllegalStateException("No 'esModule' system property set"))

  def getRootProject: File = {
    Option(System.getProperty("project.dir"))
      .map(projectDir => Paths.get(projectDir).toFile)
      .getOrElse(new File("."))
  }
}

class RorPluginGradleProject(val name: String) {
  private val project = esProject(name)
  private val esProjectProperties =
    GradleProperties
      .create(project).asScala
      .getOrElse(throw new IllegalStateException("cannot load '" + name + "' project gradle.properties file"))
  private val rootProjectProperties =
    GradleProperties
      .create(RorPluginGradleProject.getRootProject).asScala
      .getOrElse(throw new IllegalStateException("cannot load root project gradle.properties file"))

  def assemble: Option[File] = {
    runTask(name + ":ror")
    val plugin = new File(project, "build/distributions/" + pluginName)
    if (!plugin.exists) None
    else Some(plugin)
  }

  def getESVersion: String = esProjectProperties.getProperty("esVersion")

  private def esProject(esProjectName: String) = new File(RorPluginGradleProject.getRootProject, esProjectName)

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

