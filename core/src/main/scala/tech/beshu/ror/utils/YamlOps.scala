package tech.beshu.ror.utils

import io.circe.Json
import tech.beshu.ror.com.fasterxml.jackson.databind.ObjectMapper
import tech.beshu.ror.com.fasterxml.jackson.dataformat.yaml.YAMLMapper

object YamlOps {

  private val objectMapper = new ObjectMapper()
  private val yamlMapper = new YAMLMapper()

  def jsonToYamlString(json: Json): String = {
    yamlMapper
      .writeValueAsString(objectMapper.readTree(json.noSpaces))
      .replace("---\n", "")
  }
}
