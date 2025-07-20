/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.utils.containers.images

import better.files.*
import cats.data.NonEmptyList
import com.typesafe.scalalogging.LazyLogging
import os.Path
import tech.beshu.ror.utils.containers.ContainerUtils
import tech.beshu.ror.utils.containers.images.Elasticsearch.*
import tech.beshu.ror.utils.misc.Version

object Elasticsearch {

  final case class Config(clusterName: String,
                          nodeName: String,
                          masterNodes: NonEmptyList[String],
                          additionalElasticsearchYamlEntries: Map[String, String],
                          envs: Map[String, String],
                          esInstallationType: EsInstallationType)

  extension (config: Config)
    def esConfigDir: Path = config.esInstallationType match {
      case EsInstallationType.EsDockerImage => os.root / "usr" / "share" / "elasticsearch" / "config"
      case EsInstallationType.UbuntuDockerImageWithEsFromApt => os.root / "etc" / "elasticsearch"
    }

  sealed trait EsInstallationType

  object EsInstallationType {
    case object EsDockerImage extends EsInstallationType

    case object UbuntuDockerImageWithEsFromApt extends EsInstallationType
  }

  lazy val esDir: Path = os.root / "usr" / "share" / "elasticsearch"

  trait Plugin {
    def updateEsImage(image: DockerImageDescription, config: Config): DockerImageDescription

    def updateEsConfigBuilder(builder: EsConfigBuilder): EsConfigBuilder

    def updateEsJavaOptsBuilder(builder: EsJavaOptsBuilder): EsJavaOptsBuilder
  }

  private[images] def fromResourceBy(name: String): File = {
    scala.util.Try(ContainerUtils.getResourceFile(s"/$name"))
      .map(_.toScala)
      .get
  }

  def create(esVersion: String, config: Config): Elasticsearch = {
    new Elasticsearch(esVersion, config)
  }
}

class Elasticsearch(esVersion: String,
                    config: Config,
                    plugins: Seq[Plugin],
                    customEntrypoint: Option[Path])
  extends LazyLogging {

  def this(esVersion: String, config: Config) = {
    this(esVersion, config, Seq.empty, None)
  }

  def when[T](opt: Option[T], f: (Elasticsearch, T) => Elasticsearch): Elasticsearch = {
    opt match {
      case Some(t) => f(this, t)
      case None => this
    }
  }

  def install(plugin: Plugin): Elasticsearch = {
    new Elasticsearch(esVersion, config, plugins :+ plugin, customEntrypoint)
  }

  def setEntrypoint(entrypoint: Path): Elasticsearch = {
    new Elasticsearch(esVersion, config, plugins, Some(entrypoint))
  }

  def toDockerImageDescription: DockerImageDescription = config.esInstallationType match {
    case EsInstallationType.EsDockerImage =>
      toOfficialEsImageBasedDockerImageDescription
    case EsInstallationType.UbuntuDockerImageWithEsFromApt =>
      toUbuntuWithAptEsDockerImageDescription
  }

  private def toOfficialEsImageBasedDockerImageDescription: DockerImageDescription = {
    DockerImageDescription
      .create(s"docker.elastic.co/elasticsearch/elasticsearch:$esVersion", customEntrypoint)
      .copyFile(
        destination = config.esConfigDir / "elasticsearch.yml",
        file = esConfigFileBasedOn(config, updateEsConfigBuilderFromPlugins)
      )
      .copyFile(
        destination = config.esConfigDir / "log4j2.properties",
        file = log4jFileFromResources
      )
      .user("root")
      // Package tar is required by the RorToolsAppSuite, and the ES >= 9.x is based on
      // Red Hat Universal Base Image 9 Minimal, which does not contain it.
      .runWhen(Version.greaterOrEqualThan(esVersion, 9, 0, 0), "microdnf install -y tar")
      .run(s"chown -R elasticsearch:elasticsearch ${config.esConfigDir.toString()}")
      .addEnvs(config.envs + ("ES_JAVA_OPTS" -> javaOptsBasedOn(withEsJavaOptsBuilderFromPlugins)))
      .installPlugins()
      .user("elasticsearch")
  }

  private def toUbuntuWithAptEsDockerImageDescription: DockerImageDescription = {
    val esMajorVersion: String = esVersion.split("\\.")(0) + ".x"
    DockerImageDescription
      .create("ubuntu:24.04", customEntrypoint)
      .user("root")
      .run("apt update")
      .run("apt install -y ca-certificates gnupg2 curl apt-transport-https")
      .run("curl -fsSL https://artifacts.elastic.co/GPG-KEY-elasticsearch | apt-key add -")
      .run(s"""echo "deb https://artifacts.elastic.co/packages/$esMajorVersion/apt stable main" > /etc/apt/sources.list.d/elastic-$esMajorVersion.list""")
      .run(s"""apt update && apt install -y --no-install-recommends -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" elasticsearch=$esVersion""")
      .run("apt clean && rm -rf /var/lib/apt/lists/*")
      .user("elasticsearch")
      .setCommand("/usr/share/elasticsearch/bin/elasticsearch")
      .copyFile(
        destination = config.esConfigDir / "elasticsearch.yml",
        file = esConfigFileBasedOn(config, updateEsConfigBuilderFromPlugins)
      )
      .copyFile(
        destination = config.esConfigDir / "log4j2.properties",
        file = log4jFileFromResources
      )
      .user("root")
      // ES is started as Docker CMD, so elasticsearch user must have permission to read ES files.
      // In standard Ubuntu with ES from apt it is not necessary, because ES is executed from systemd
      .run(s"chown -R elasticsearch:elasticsearch ${esDir.toString()}")
      .run(s"chown -R elasticsearch:elasticsearch ${config.esConfigDir.toString()}")
      .run("rm /etc/elasticsearch/elasticsearch.keystore")
      .addEnvs(config.envs + ("ES_JAVA_OPTS" -> javaOptsBasedOn(withEsJavaOptsBuilderFromPlugins)))
      .installPlugins()
      .user("elasticsearch")
  }

  private implicit class InstallPlugins(val image: DockerImageDescription) {
    def installPlugins(): DockerImageDescription = {
      plugins
        .foldLeft(image) {
          case (currentImage, plugin) => plugin.updateEsImage(currentImage, config)
        }
    }
  }

  private def updateEsConfigBuilderFromPlugins(builder: EsConfigBuilder) = {
    plugins
      .map(p => p.updateEsConfigBuilder(_))
      .foldLeft(builder) { case (currentBuilder, update) => update(currentBuilder) }
  }

  private def withEsJavaOptsBuilderFromPlugins(builder: EsJavaOptsBuilder) = {
    plugins
      .map(p => p.updateEsJavaOptsBuilder(_))
      .foldLeft(builder) { case (currentBuilder, update) => update(currentBuilder) }
  }

  private def esConfigFileBasedOn(config: Config,
                                  withEsConfigBuilder: EsConfigBuilder => EsConfigBuilder) = {
    val file = File
      .newTemporaryFile()
      .appendLines(
        withEsConfigBuilder(baseEsConfigBuilder(config)).entries: _*
      )
    logger.info(s"elasticsearch.yml content:\n${file.contentAsString}")
    file
  }

  private def baseEsConfigBuilder(config: Config) = {
    EsConfigBuilder
      .empty
      .add(s"node.name: ${config.nodeName}")
      .add(s"cluster.name: ${config.clusterName}")
      .add("network.host: 0.0.0.0")
      .add("path.repo: /tmp")
      .addWhen(Version.lowerThan(esVersion, 8, 0, 0),
        entry = "bootstrap.system_call_filter: false" // because of issues with Rosetta 2 on Mac OS
      )
      .add("cluster.routing.allocation.disk.threshold_enabled: false")
      .addWhen(Version.greaterOrEqualThan(esVersion, 7, 6, 0),
        entry = "indices.lifecycle.history_index_enabled: false"
      )
      .addWhen(Version.greaterOrEqualThan(esVersion, 7, 0, 0),
        entry = s"discovery.seed_hosts: ${config.masterNodes.toList.mkString(",")}",
        orElseEntry = s"discovery.zen.ping.unicast.hosts: ${config.masterNodes.toList.mkString(",")}"
      )
      .addWhen(Version.greaterOrEqualThan(esVersion, 7, 0, 0),
        entry = s"cluster.initial_master_nodes: ${config.masterNodes.toList.mkString(",")}",
        orElseEntry = "node.master: true"
      )
      .addWhen(Version.greaterOrEqualThan(esVersion, 7, 14, 0),
        entry = "ingest.geoip.downloader.enabled: false"
      )
      .addWhen(Version.greaterOrEqualThan(esVersion, 8, 0, 0),
        entry = "action.destructive_requires_name: false"
      )
      .addWhen(Version.lowerThan(esVersion, 8, 0, 0),
        entry = "xpack.monitoring.enabled: false"
      )
      .add(
        entries = config.additionalElasticsearchYamlEntries.map { case (key, value) => s"$key: $value" }
      )
  }

  private def log4jFileFromResources = {
    fromResourceBy(
      name = if (Version.greaterOrEqualThan(esVersion, 7, 10, 0)) "log4j2_es_7.10_and_newer.properties"
      else "log4j2_es_before_7.10.properties"
    )
  }

  private def javaOptsBasedOn(withEsJavaOptsBuilder: EsJavaOptsBuilder => EsJavaOptsBuilder) = {
    withEsJavaOptsBuilder(baseJavaOptsBuilder)
      .options
      .mkString(" ")
  }

  private def baseJavaOptsBuilder = {
    EsJavaOptsBuilder
      .empty
      .add("-Xms512m")
      .add("-Xmx512m")
      .add("-Djava.security.egd=file:/dev/./urandoms")
      .add("-Xdebug", s"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=${xDebugAddressBasedOn(esVersion)}")
  }

  private def xDebugAddressBasedOn(esVersion: String) = {
    if (Version.greaterOrEqualThan(esVersion, 6, 3, 0)) "*:8000" else "8000"
  }
}

final case class EsConfigBuilder(entries: Seq[String]) {

  def add(entry: String): EsConfigBuilder = add(Seq(entry))

  def add(entries: Iterable[String]): EsConfigBuilder = {
    this.copy(entries = this.entries ++ entries)
  }

  def remove(entry: String): EsConfigBuilder = {
    this.copy(entries = this.entries.filterNot(_ == entry))
  }

  def addWhen(condition: Boolean, entry: => String): EsConfigBuilder = {
    if (condition) add(entry)
    else this
  }

  def addWhen(condition: Boolean,
              entry: => String,
              orElseEntry: => String): EsConfigBuilder = {
    if (condition) add(entry)
    else add(orElseEntry)
  }
}

object EsConfigBuilder {
  def empty: EsConfigBuilder = EsConfigBuilder(Seq.empty)
}

final case class EsJavaOptsBuilder(options: Seq[String]) {

  def add(option: String*): EsJavaOptsBuilder = {
    this.copy(options = this.options ++ option.toSeq)
  }
}

object EsJavaOptsBuilder {
  def empty: EsJavaOptsBuilder = EsJavaOptsBuilder(Seq.empty)
}
