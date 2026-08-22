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
  * Every image this mirrors is named at its call site, on purpose. testcontainers can prefix names by itself, from
  * TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX, but it prefixes EVERY name without a registry host. A locally built name looks
  * the same as a Docker Hub name, so it rewrote our own `ror-it-es:<hash>` image and then tried to pull it from the
  * mirror. We therefore keep our own variable, and each call site says what it wants mirrored.
  *
  * This explicit rewrite changes the image's registry identity, unlike a daemon or BuildKit mirror. Docker therefore
  * cannot fall back to docker.io if the mirror does not serve the requested tag. Keep the call sites limited to images
  * known to be available from the configured mirror.
  */
object DockerHubMirror {

  private val envName = "ROR_DOCKER_HUB_MIRROR_PREFIX"

  /** The image name to pull. It is unchanged when no mirror is configured, and when the name already carries a registry
    * host. The mirror serves docker.io only, so `docker.elastic.co/elasticsearch/elasticsearch` and
    * `ghcr.io/shopify/toxiproxy` must pass through untouched.
    */
  def applyTo(imageName: String): String = {
    val prefix = configuredPrefix
    if (prefix.isEmpty || hasRegistryHost(imageName) || imageName.startsWith(prefix)) imageName
    else if (imageName.contains("/")) prefix + imageName
    else prefix + "library/" + imageName
  }

  /** The configured prefix, or an empty string when there is none. It always ends with a slash. */
  private def configuredPrefix: String = {
    Option(System.getenv(envName))
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
