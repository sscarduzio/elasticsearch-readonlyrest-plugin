package tech.beshu.ror.utils.containers.generic

import com.dimafeng.testcontainers.SingleContainer
import org.testcontainers.containers.GenericContainer

object ContainerOps {

  implicit class RichContainer(val container: SingleContainer[GenericContainer[_]]) extends AnyVal {

    def bridgeIpAddress: Option[String] =
      Option(container.containerInfo)
        .map(_.getNetworkSettings
          .getNetworks
          .get("bridge")
          .getIpAddress
        )
  }
}