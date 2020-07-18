package tech.beshu.ror.configuration.loader.distributed.dto

import io.circe.generic.extras.ConfiguredJsonCodec
import tech.beshu.ror.configuration.loader.LoadedConfig

@ConfiguredJsonCodec
sealed trait LoadedConfigDTO

object LoadedConfigDTO {
  def create(o: LoadedConfig[String]): LoadedConfigDTO = o match {
    case LoadedConfig.FileConfig(value) => FILE_CONFIG(value)
    case LoadedConfig.ForcedFileConfig(value) => FORCED_FILE_CONFIG(value)
    case LoadedConfig.IndexConfig(indexName, value) => INDEX_CONFIG(indexName.index.value.value, value)
  }
  final case class FILE_CONFIG(config: String) extends LoadedConfigDTO
  final case class FORCED_FILE_CONFIG(config: String) extends LoadedConfigDTO
  final case class INDEX_CONFIG(indexName: String, config: String) extends LoadedConfigDTO
}

