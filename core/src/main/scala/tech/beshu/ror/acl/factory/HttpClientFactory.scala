package tech.beshu.ror.acl.factory

import com.softwaremill.sttp.SttpBackend
import monix.eval.Task
import tech.beshu.ror.acl.factory.HttpClientFactory.{Config, HttpClient}

class HttpClientFactory {

  def create(config: Config): HttpClient = ???

}

object HttpClientFactory {
  type HttpClient = SttpBackend[Task, Nothing]
  final case class Config(validate: Boolean)
}