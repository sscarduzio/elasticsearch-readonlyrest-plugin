package tech.beshu.ror.utils.containers

import com.dimafeng.testcontainers.SingleContainer
import org.testcontainers.containers.GenericContainer

import scala.collection.JavaConverters._
import scala.util.Try

object ContainerOps {

  implicit class RichContainer(val container: SingleContainer[GenericContainer[_]]) extends AnyVal {

    def ipAddressFromFirstNetwork: Option[String] =
      Try(container.containerInfo)
        .map(_
          .getNetworkSettings
          .getNetworks.asScala
          .head._2
          .getIpAddress
        ).toOption
  }
}