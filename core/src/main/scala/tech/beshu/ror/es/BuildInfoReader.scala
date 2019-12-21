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
package tech.beshu.ror.es

import java.util.{Objects, Properties}

final case class BuildInfo(esVersion: String, pluginVersion: String)
object BuildInfoReader {
  private val filename = "git.properties"

  def create(filename: String = filename): BuildInfo = {
    val props = loadProperties(filename)
    val esVersion: String = getProperty(props, "es_version")
    val pluginVersion: String = getProperty(props, "plugin_version")
    BuildInfo(esVersion, pluginVersion)
  }

  private def loadProperties(filename: String) = {
    val stream = Objects.requireNonNull(this.getClass.getResourceAsStream(s"/$filename"), s"file '$filename' is expected to be present in plugin jar, but it wasn't found.")
    val props = new Properties()
    try {
      props.load(stream)
    } finally {
      stream.close()
    }
    props
  }

  private def getProperty(props: Properties, propertyName: String) =
    Objects.requireNonNull(props.getProperty(propertyName), s"Property value '$propertyName' have to be defined")

}

