package tech.beshu.ror.utils.misc

import java.nio.file.Path
import better.files.File

object Resources {

  def getResourcePath(resource: String): Path = {
    File(getClass.getResource(resource).getPath).path
  }

  def getResourceContent(resource: String): String = {
    File(getResourcePath(resource)).contentAsString
  }

}
