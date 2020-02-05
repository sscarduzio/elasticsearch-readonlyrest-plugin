package tech.beshu.ror.utils.containers.generic

import java.util.Optional

import tech.beshu.ror.utils.containers.generic.ClientProvider.adminCredentials
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Tuple

trait ClientProvider {
  def client(user: String, pass: String): RestClient

  def adminClient: RestClient = client(adminCredentials._1, adminCredentials._2)
}

trait TargetEsContainer {
  def targetEsContainer: RorContainer
}

trait CallingEsDirectly extends ClientProvider {
  this: TargetEsContainer =>

  override def client(user: String, pass: String): RestClient = targetEsContainer.client(user, pass)
}

trait CallingProxy extends ClientProvider {
  def proxyPort: Int

  override def client(user: String, pass: String): RestClient = new RestClient(false, "localhost", proxyPort, Optional.of(Tuple.from(user, pass)))
}

object ClientProvider {
  val adminCredentials: (String, String) = ("admin", "container")
}

