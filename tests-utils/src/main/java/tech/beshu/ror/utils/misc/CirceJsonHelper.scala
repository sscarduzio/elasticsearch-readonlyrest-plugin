package tech.beshu.ror.utils.misc

import io.circe.Json
import io.circe.yaml.parser

object CirceJsonHelper {

  def jsonFrom(yaml: String): Json = {
    parser.parse(yaml).right.get
  }

}
