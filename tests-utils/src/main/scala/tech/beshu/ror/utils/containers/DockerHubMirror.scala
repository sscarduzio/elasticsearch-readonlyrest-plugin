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
package tech.beshu.ror.utils.containers

/** Puts the configured mirror in front of a bare docker.io image name.
  *
  * Docker Hub limits pulls per IP address and ignores the account when it does. A CI runner shares its address with
  * other tenants, so a neighbour can make our pull fail with a bare 429. Docker Hub also counts every pull against one
  * account quota, and this test suite is its heaviest user. A cache answers from a different host. See
  * build-base/buildkitd.toml.
  *
  * testcontainers already rewrites image names from TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX, but only for an image it
  * pulls itself. It does not rewrite a FROM inside an ImageFromDockerfile build, because that Dockerfile goes to the
  * Docker daemon as text. Three call sites build an image that way and use this object: WireMockContainer (from Java),
  * Elasticsearch and DnsServerContainer.
  *
  * It reads the same variable testcontainers reads, so one setting controls the whole suite. ci/configure-docker.sh
  * sets it. The prefix needs a trailing slash: "mirror.gcr.io/".
  */
object DockerHubMirror {

  private val envName = "TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX"
  private val propertyName = "hub.image.name.prefix"

  /** The image name to pull. It is unchanged when no mirror is configured, and when the name already carries a registry
    * host. The mirror serves docker.io only, so `docker.elastic.co/elasticsearch/elasticsearch` and
    * `ghcr.io/shopify/toxiproxy` must pass through untouched.
    */
  def applyTo(imageName: String): String = {
    val prefix = configuredPrefix
    if (prefix.isEmpty || hasRegistryHost(imageName) || imageName.startsWith(prefix)) imageName
    else prefix + imageName
  }

  /** The configured prefix, or an empty string when there is none. It always ends with a slash. */
  def configuredPrefix: String = {
    Option(System.getProperty(propertyName))
      .orElse(Option(System.getenv(envName)))
      .map(_.trim)
      .filter(_.nonEmpty) match {
      case Some(prefix) if prefix.endsWith("/") => prefix
      case Some(prefix)                         => prefix + "/"
      case None                                 => ""
    }
  }

  // Docker's own rule for telling a registry host from the first path element of a Docker Hub name: the first element
  // is a host when it holds a dot or a port, or when it is exactly "localhost". "osixia/openldap" is thus a Docker Hub
  // name, and "docker.elastic.co/x" is not.
  private def hasRegistryHost(imageName: String): Boolean = {
    imageName.indexOf('/') match {
      case -1 =>
        false
      case firstSlash =>
        val candidate = imageName.substring(0, firstSlash)
        candidate.contains(".") || candidate.contains(":") || candidate == "localhost"
    }
  }

}
