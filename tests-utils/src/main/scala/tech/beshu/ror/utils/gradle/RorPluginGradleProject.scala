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
import com.typesafe.scalalogging.LazyLogging
import org.gradle.tooling.GradleConnector

import java.io.File as JFile
import java.nio.file.Paths
import scala.util.Try

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
}

class RorPluginGradleProject(val moduleName: String) extends LazyLogging {
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
    logger.info(s"Assembling ROR in module $moduleName")
    runTask(moduleName + ":packageRorPlugin")
    val plugin = new JFile(project, "build/distributions/" + pluginName)
    logger.info(s"Finished assembling ROR in module $moduleName")
    if (!plugin.exists) None
    else Some(plugin)
  }

  def getModuleESVersion: String =
    Option(System.getProperty("esVersion"))
      .filter(_ => isExplicitlyTargetedModule)
      .getOrElse(esProjectProperties.getProperty("latestSupportedEsVersion"))

  private def isExplicitlyTargetedModule: Boolean =
    moduleName == System.getProperty("esModule")

  private def esProject(esProjectName: String) = new JFile(RorPluginGradleProject.getRootProject, esProjectName)

  private def pluginName =
    s"${rootProjectProperties.getProperty("pluginName")}-${rootProjectProperties.getProperty("pluginVersion")}_es$getModuleESVersion.zip"

  private def runTask(task: String): Unit = {
    val connector = GradleConnector.newConnector.forProjectDirectory(RorPluginGradleProject.getRootProject)
    // On CI the toolchains image bakes the Gradle distribution + dependency cache into GRADLE_USER_HOME
    // and marks it with a `.ror-ci-baked` sentinel (written by ci/toolchains/JdkToolchains.Dockerfile only after the
    // cache passed offline validation). Point this nested Tooling-API build at that home so it reuses
    // the baked distribution/cache instead of downloading gradle-x-all.zip from services.gradle.org;
    // anything missing from the cache (e.g. a dep bumped since the last image rebuild) falls back to
    // the network naturally. The sentinel (not bare directory existence) is the contract: a developer
    // with a relocated GRADLE_USER_HOME keeps the default behaviour.
    val bakedGradleHome = Option(System.getenv("GRADLE_USER_HOME"))
      .map(new JFile(_))
      .filter(home => new JFile(home, ".ror-ci-baked").isFile)
    bakedGradleHome.foreach(connector.useGradleUserHomeDir)
    val connect = Try(connector.connect())
    val result = connect.map { c =>
      val args =
        if (isExplicitlyTargetedModule) Option(System.getProperty("esVersion")).map(v => s"-PesVersion=$v").toList else Nil
      val build = c.newBuild().forTasks(task)
      (if (args.nonEmpty) build.withArguments(args*) else build).run()
    }
    connect.map(_.close())
    result.fold(throw _, _ => ())
  }
}

