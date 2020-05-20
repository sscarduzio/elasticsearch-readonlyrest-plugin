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

import org.gradle.tooling.GradleConnector

import scala.util.Try

class RorProxyGradleProject(val moduleName: String) {

  private val project = esProject(moduleName)
  private val esProjectProperties =
    GradleProperties
      .create(project)
      .getOrElse(throw new IllegalStateException("cannot load '" + moduleName + "' project gradle.properties file"))
  private val rootProjectProperties =
    GradleProperties
      .create(RorPluginGradleProject.getRootProject)
      .getOrElse(throw new IllegalStateException("cannot load root project gradle.properties file"))

  def assemble: Option[JFile] = {
    runTask(moduleName + ":rorproxy")
    val proxyJar = new JFile(project, "build/libs/" + proxyName)
    if (!proxyJar.exists) None
    else Some(proxyJar)
  }

  def getESVersion: String = esProjectProperties.getProperty("esVersion")

  private def esProject(esProjectName: String) = new JFile(RorPluginGradleProject.getRootProject, esProjectName)

  private def proxyName =
    s"${rootProjectProperties.getProperty("pluginName")}-proxy-${rootProjectProperties.getProperty("pluginVersion")}_es$getESVersion.jar"

  private def runTask(task: String): Unit = {
    val connector = GradleConnector.newConnector.forProjectDirectory(RorPluginGradleProject.getRootProject)
    val connect = Try(connector.connect())
    val result = connect.map(_.newBuild().forTasks(task).run())
    connect.map(_.close())
    result.fold(throw _, _ => ())
  }
}

