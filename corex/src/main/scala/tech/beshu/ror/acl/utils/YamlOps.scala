package tech.beshu.ror.acl.utils

import io.circe.Json
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.databind.ObjectMapper

object YamlOps {

  private val objectMapper = new ObjectMapper()
  private val yamlMapper = new YAMLMapper()

  def jsonToYamlString(json: Json): String = {
    yamlMapper
      .writeValueAsString(objectMapper.readTree(json.noSpaces))
      .replace("---\n", "")
  }
}
