package tech.beshu.ror.utils.containers

import com.dimafeng.testcontainers.GenericContainer
import com.github.dockerjava.api.model.{ExposedPort, InternetProtocol}
import org.testcontainers.images.builder.ImageFromDockerfile

import scala.collection.JavaConverters._
import scala.language.{implicitConversions, postfixOps}

class DnsServerContainer(srvServicePort: Int)
  extends GenericContainer(
    dockerImage = new ImageFromDockerfile()
      .withFileFromClasspath("Dockerfile", "coredns-image/Dockerfile")
      .withFileFromClasspath("Corefile", "coredns-image/Corefile")
      .withFileFromString("conf",
      s"""
          |$$ORIGIN example.org.
          |@	3600	IN	SOA someorg.org.  someorg.com.  (2017042745 7200 3600 1209600 3600)
          |_ldap._tcp.	 86400	IN	SRV	10	60     $srvServicePort	localhost.
          |""".stripMargin)
  ) {

  def dnsPort: Int = {
    // This is hack to obtain mapping to UDP port as testcontainers doesn't allow explicit mapping of UDP ports
    // although it is mapping each port exposed by container(even UDP).
    this.containerInfo.getNetworkSettings.getPorts.getBindings.asScala.get(new ExposedPort(53, InternetProtocol.UDP)).get.head.getHostPortSpec.toInt
  }

  override def stop(): Unit = {
    this.container.stop()
  }

}