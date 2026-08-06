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
package tech.beshu.ror.integration.suites.base

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, PluginTestSupport}
import tech.beshu.ror.utils.containers.*
import tech.beshu.ror.utils.elasticsearch.{ElasticsearchTweetsInitializer, IndexManager}
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

/** Proves that a TLS client certificate reaches the ACL and identifies the caller.
  *
  * The certificates are described in `tests-utils/src/main/resources/pki/README.md`; their
  * distinguished names and the `pkis` provider in `/pki/readonlyrest.yml` are one contract.
  *
  * Subclasses pick which component terminates TLS. That is the point of the split: the certificate is
  * read off the same Netty channel either way, but only one of the two paths runs through a transport
  * ReadonlyREST itself installed, so both have to be exercised.
  */
abstract class PkiAuthSuite
    extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with PluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with SingleClientSupport
    with BeforeAndAfterAll
    with CustomScalaTestMatchers {

  override implicit val rorSettingsFileName: String = "/pki/readonlyrest.yml"

  protected def pkiSecurityType: SecurityType

  override def clusterContainer: EsClusterContainer = pkiClusterContainer

  override def targetEs: EsContainer = pkiClusterContainer.nodes.head

  lazy val pkiClusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings.create(
      clusterName = "pki_cluster",
      securityType = pkiSecurityType,
      nodeDataInitializer = ElasticsearchTweetsInitializer
    )
  )

  "A client authenticated by its certificate" should {
    "be allowed by the groups its certificate carries" in {
      val indexManager = indexManagerFor("/pki/pki-svc-logstash.jks")

      indexManager.getIndex("twitter") should have statusCode 200
    }
    "be forbidden where those groups do not reach" in {
      val indexManager = indexManagerFor("/pki/pki-svc-logstash.jks")

      // 404, not 403: the certificate did authenticate the caller, and ReadonlyREST hides an index the
      // caller may not see by rewriting it to a name that does not exist rather than admitting it exists
      indexManager.getIndex("facebook") should have statusCode 404
    }
    "be allowed by name alone, without any groups" in {
      val indexManager = indexManagerFor("/pki/pki-svc-dashboard.jks")

      indexManager.getIndex("facebook") should have statusCode 200
    }
    "be forbidden when its certificate sits outside the provider's subject_dn_base" in {
      // trusted by the same CA and carrying the svc-ingest role, but issued into the People branch
      val indexManager = indexManagerFor("/pki/pki-jsmith.jks")

      indexManager.getIndex("twitter") should have statusCode 403
    }
  }

  "A client with no certificate" should {
    "fall through to a password block, which is what lets both share one port" in {
      val indexManager = new IndexManager(basicAuthClient("analyst", "passwd"), esVersionUsed)

      indexManager.getIndex("twitter") should have statusCode 200
    }
    "be rejected when it offers no other credential either" in {
      val indexManager = new IndexManager(noBasicAuthClient, esVersionUsed)

      // 403 rather than 401: ReadonlyREST only asks for credentials when basic-auth prompting is on
      indexManager.getIndex("twitter") should have statusCode 403
    }
  }

  "A client whose certificate was issued by an untrusted CA" should {
    "get nowhere, because the node never accepts the certificate" in {
      // The same subject DN as svc-logstash, from a CA the node does not trust - which is exactly why a
      // subject DN alone proves nothing and issuer_dn exists as a second constraint.
      // The node advertises the CAs it will accept, this certificate matches none of them, so the client
      // withholds it and the request arrives unauthenticated rather than failing the handshake.
      val indexManager = indexManagerFor("/pki/pki-rogue.jks")

      indexManager.getIndex("twitter") should have statusCode 403
    }
  }

  private def indexManagerFor(clientKeystoreResource: String) =
    new IndexManager(clientCertificateAuthClient(clientKeystoreResource), esVersionUsed)

}
