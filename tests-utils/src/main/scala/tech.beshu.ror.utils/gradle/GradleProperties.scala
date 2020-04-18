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