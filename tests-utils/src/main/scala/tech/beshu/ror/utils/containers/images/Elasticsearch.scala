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

import better.files._
import cats.data.NonEmptyList
import com.typesafe.scalalogging.LazyLogging
import os.Path
import tech.beshu.ror.utils.containers.ContainerUtils
import tech.beshu.ror.utils.containers.images.Elasticsearch._
import tech.beshu.ror.utils.misc.Version

object Elasticsearch {

  final case class Config(clusterName: String,
                          nodeName: String,
                          masterNodes: NonEmptyList[String],
                          additionalElasticsearchYamlEntries: Map[String, String],
                          envs: Map[String, String])

  lazy val esDir: Path = os.root / "usr" / "share" / "elasticsearch"
  lazy val configDir: Path = esDir / "config"

  trait Plugin {
    def updateEsImage(image: DockerImageDescription): DockerImageDescription
    def updateEsConfigBuilder(builder: EsConfigBuilder): EsConfigBuilder
    def updateEsJavaOptsBuilder(builder: EsJavaOptsBuilder): EsJavaOptsBuilder
  }

  private [images] def fromResourceBy(name: String): File = {
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
                    plugins: Seq[Plugin])
  extends LazyLogging {

  def this(esVersion: String, config: Config) {
    this(esVersion, config, Seq.empty)
  }

  def install(plugin: Plugin): Elasticsearch = {
    new Elasticsearch(esVersion, config, plugins :+ plugin)
  }

  def toDockerImageDescription: DockerImageDescription = {
    DockerImageDescription
      .create(image = s"docker.elastic.co/elasticsearch/elasticsearch:$esVersion")
      .copyFile(
        destination = configDir / "elasticsearch.yml",
        file = esConfigFileBasedOn(config, updateEsConfigBuilderFromPlugins)
      )
      .copyFile(
        destination = configDir / "log4j2.properties",
        file = log4jFileNameBaseOn(config)
      )
      .user("root")
      .run(s"chown -R elasticsearch:elasticsearch ${configDir.toString()}")
      .addEnvs(config.envs + ("ES_JAVA_OPTS" -> javaOptsBasedOn(config, withEsJavaOptsBuilderFromPlugins)))
      .installPlugins()
      .user("elasticsearch")
  }

  private implicit class InstallPlugins(val image: DockerImageDescription) {
    def installPlugins(): DockerImageDescription = {
      plugins
        .foldLeft(image) {
          case (currentImage, plugin) => plugin.updateEsImage(currentImage)
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
        orElseEntry = s"node.master: true"
      )
      .addWhen(Version.greaterOrEqualThan(esVersion, 7, 14, 0),
        entry = s"ingest.geoip.downloader.enabled: false"
      )
      .addWhen(Version.greaterOrEqualThan(esVersion, 8, 0, 0),
        entry = s"action.destructive_requires_name: false"
      )
      .add(
        entries = config.additionalElasticsearchYamlEntries.map { case (key, value) => s"$key: $value" }
      )
  }

  private def log4jFileNameBaseOn(config: Config) = {
    fromResourceBy(
      name = if (Version.greaterOrEqualThan(esVersion, 7, 10, 0)) "log4j2_es_7.10_and_newer.properties"
      else "log4j2_es_before_7.10.properties"
    )
  }

  private def javaOptsBasedOn(config: Config,
                              withEsJavaOptsBuilder: EsJavaOptsBuilder => EsJavaOptsBuilder) = {
    withEsJavaOptsBuilder(baseJavaOptsBuilder(config))
      .options
      .mkString(" ")
  }

  private def baseJavaOptsBuilder(config: Config) = {
    EsJavaOptsBuilder
      .empty
      .add("-Xms1g")
      .add("-Xmx1g")
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