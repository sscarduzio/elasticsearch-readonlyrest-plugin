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

import java.io.{File, FileInputStream}
import java.util.Properties

import scala.util.Try

class GradleProperties private(properties: Properties) {
  def getProperty(key: String): String = properties.getProperty(key)
}

object GradleProperties {

  def create(project: File): Option[GradleProperties] = Try {
    val prop = new Properties
    val file = new File(project, "gradle.properties")
    val input = new FileInputStream(file)
    prop.load(input)
    new GradleProperties(prop)
  }.toOption
}