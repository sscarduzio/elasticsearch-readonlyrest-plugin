package tech.beshu.ror.utils.containers

import com.dimafeng.testcontainers.GenericContainer

import scala.collection.JavaConverters._
import scala.language.{implicitConversions, postfixOps}

class DnsServerContainer(srvServicePort: Int)
  extends GenericContainer(
    dockerImage = "mycoredns:latest",
    env = Map("PORT" -> srvServicePort.toString)
  ) {

  def dnsPort: Int = {
    // This is hack to obtain mapping to UDP port as testcontainers doesn't allow explicit mapping of UDP ports
    // although it is mapping each port exposed by container(even UDP).
    // This code gets port value of only one mapped port which should be 53/udp
    this.containerInfo.getNetworkSettings.getPorts.getBindings.asScala.head._2.head.getHostPortSpec.toInt
  }

  override def stop(): Unit = {
    this.container.stop()
  }

}