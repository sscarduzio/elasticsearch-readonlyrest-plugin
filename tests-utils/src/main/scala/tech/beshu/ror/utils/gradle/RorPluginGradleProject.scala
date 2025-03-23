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

import better.files.*
import tech.beshu.ror.utils.gradle.RorPluginGradleProject.PluginFiles

import java.io.File as JFile
import java.nio.file.Paths

object RorPluginGradleProject {
  def fromSystemProperty: RorPluginGradleProject =
    Option(System.getProperty("esModule"))
      .map(new RorPluginGradleProject(_))
      .getOrElse(throw new IllegalStateException("No 'esModule' system property set"))

  def customModule(moduleName: String): RorPluginGradleProject =
    new RorPluginGradleProject(moduleName)

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
      .filter(_.matches("^es\\d{2,3}x$"))
      .toList

  final case class PluginFiles(plugin: JFile,
                               rorProperties: JFile,
                               rorSecurityPolicy: JFile)

}

class RorPluginGradleProject(val moduleName: String) {
  private val project = esProject(moduleName)
  private val esProjectProperties =
    GradleProperties
      .create(project)
      .getOrElse(throw new IllegalStateException("cannot load '" + moduleName + "' project gradle.properties file"))
  private val rootProjectProperties =
    GradleProperties
      .create(RorPluginGradleProject.getRootProject)
      .getOrElse(throw new IllegalStateException("cannot load root project gradle.properties file"))

  def getPluginFiles: Option[PluginFiles] = {
    val plugin = new JFile(project, "build/distributions/" + pluginVersion + ".zip")
    val rorProperties = new JFile(project, "build/tmp/" + pluginVersion + "/plugin-descriptor.properties")
    val rorSecurityPolicy = new JFile(project, "build/tmp/" + pluginVersion + "/plugin-security.policy")
    if (!plugin.exists || !rorProperties.exists() || !rorSecurityPolicy.exists()) None
    else Some(PluginFiles(plugin, rorProperties, rorSecurityPolicy))
  }

  def getModuleESVersion: String = esProjectProperties.getProperty("latestSupportedEsVersion")

  private def esProject(esProjectName: String) = new JFile(RorPluginGradleProject.getRootProject, esProjectName)

  private def pluginVersion =
    s"${rootProjectProperties.getProperty("pluginName")}-${rootProjectProperties.getProperty("pluginVersion")}_es$getModuleESVersion"

}

