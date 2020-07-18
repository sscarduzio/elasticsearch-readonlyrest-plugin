package tech.beshu.ror.configuration.loader.distributed.dto

import tech.beshu.ror.configuration.loader.LoadedConfig

object LoadedConfigError {
  def createError(error: LoadedConfig.Error): String = error match {
    case LoadedConfig.FileNotExist(path) => s"""file not exist: ${path.value}"""
    case LoadedConfig.FileParsingError(message) => s"""file parsing error: ${message}"""
    case LoadedConfig.EsFileNotExist(path) => s"""es file not exist: ${path.value}"""
    case LoadedConfig.EsFileMalformed(path, message) => s"""es file malformed: ${path} ${message}"""
    case LoadedConfig.IndexParsingError(message) => s"""index parsing error: ${message}"""
    case LoadedConfig.IndexUnknownStructure => "index unknown structure"
  }
}
