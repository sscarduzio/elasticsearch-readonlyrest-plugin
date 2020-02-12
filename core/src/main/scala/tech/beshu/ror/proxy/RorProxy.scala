package tech.beshu.ror.proxy

import better.files.File
import cats.effect.{ContextShift, IO}
import tech.beshu.ror.boot.StartingFailure
import RorProxy.CloseHandler

trait RorProxy {

  def config: RorProxy.Config

  def start: IO[Either[StartingFailure, CloseHandler]]

}

object RorProxy {
  type CloseHandler = () => IO[Unit]

  final case class Config(targetEsNode: String,
                          proxyPort: String,
                          esConfigFile: Option[File])

}
