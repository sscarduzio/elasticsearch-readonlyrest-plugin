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
package tech.beshu.ror.integration

import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.AccessControlList.RegularRequestResult.{Allowed, ForbiddenByMismatched}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{ClientCertificate, DistinguishedName, SubjectAlternativeName, User}
import tech.beshu.ror.mocks.MockEsServices.MockEsClusterService
import tech.beshu.ror.mocks.{MockEsServices, MockRequestContext}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueList

/** Exercises the whole path from a `pkis` definition to an ACL decision, including the group mapping that
  * turns certificate attributes into local groups and the mixed-mode fall-through onto a password block.
  */
class PkiAuthAccessControlTests extends AnyWordSpec with BaseYamlLoadedAccessControlTest with Inside {

  override protected def settingsYaml: String =
    """
      |readonlyrest:
      |
      |  access_control_rules:
      |
      |  - name: "Machine identities"
      |    groups: ["ingest"]
      |    indices: ["logs-*"]
      |
      |  - name: "Named service"
      |    pki_authentication:
      |      name: "service_pki"
      |      users: ["svc-dashboard"]
      |    indices: ["reports-*"]
      |
      |  - name: "Humans"
      |    auth_key: "analyst:pass"
      |    indices: ["logs-*"]
      |
      |  users:
      |  - username: "*"
      |    groups:
      |    - local_group:
      |        id: "ingest"
      |        name: "ingest"
      |      external_group_ids: ["svc-ingest", "svc-beats"]
      |    pki_auth:
      |      name: "service_pki"
      |      groups: ["svc-ingest", "svc-beats", "svc-unmapped"]
      |
      |  pkis:
      |  - name: service_pki
      |    subject_dn_base: "OU=Services,DC=corp"
      |    users:
      |      user_id_attribute: "CN"
      |    groups:
      |      group_id_attribute: "OU"
      |
    """.stripMargin

  "An ACL with a PKI provider" should {
    "allow a certificate-bearing service whose certificate carries a mapped group" in {
      val request = requestFor(
        indexName = "logs-2026",
        clientCertificate = Some(certificateWith("CN=beats-01,OU=svc-ingest,OU=Services,DC=corp"))
      )

      val (result, _) = acl.handleRegularRequest(request).runSyncUnsafe()

      inside(result) { case Allowed(blockContext) =>
        blockContext.blockMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("beats-01"))))
        blockContext.blockMetadata.availableGroups should be(UniqueList.of(group("ingest")))
      }
    }
    "allow a service named on the rule's accepted users list" in {
      val request = requestFor(
        indexName = "reports-2026",
        clientCertificate = Some(certificateWith("CN=svc-dashboard,OU=Services,DC=corp"))
      )

      val (result, _) = acl.handleRegularRequest(request).runSyncUnsafe()

      inside(result) { case Allowed(blockContext) =>
        blockContext.blockMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("svc-dashboard"))))
      }
    }
    "let a request without a certificate fall through to a password block" in {
      val request = requestFor(indexName = "logs-2026", clientCertificate = None)
        .withHeaders(basicAuthHeader("analyst:pass"))

      val (result, _) = acl.handleRegularRequest(request).runSyncUnsafe()

      inside(result) { case Allowed(blockContext) =>
        blockContext.blockMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("analyst"))))
      }
    }
    "forbid a certificate whose group is not mapped to a local group" in {
      val request = requestFor(
        indexName = "logs-2026",
        clientCertificate = Some(certificateWith("CN=beats-01,OU=svc-unmapped,OU=Services,DC=corp"))
      )

      val (result, _) = acl.handleRegularRequest(request).runSyncUnsafe()

      inside(result) { case ForbiddenByMismatched(_) => }
    }
    "forbid a certificate issued outside the scope of the provider" in {
      val request = requestFor(
        indexName = "logs-2026",
        clientCertificate = Some(certificateWith("CN=jsmith,OU=svc-ingest,OU=People,DC=corp"))
      )

      val (result, _) = acl.handleRegularRequest(request).runSyncUnsafe()

      inside(result) { case ForbiddenByMismatched(_) => }
    }
    "forbid a service that is not on the accepted users list of the rule" in {
      val request = requestFor(
        indexName = "reports-2026",
        clientCertificate = Some(certificateWith("CN=beats-01,OU=Services,DC=corp"))
      )

      val (result, _) = acl.handleRegularRequest(request).runSyncUnsafe()

      inside(result) { case ForbiddenByMismatched(_) => }
    }
  }

  private def requestFor(indexName: String, clientCertificate: Option[ClientCertificate]) = {
    val request = MockRequestContext.indices
      .copy(
        filteredIndices = Set(requestedIndex(indexName)),
        esServices = MockEsServices.`with`(
          MockEsClusterService(allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName(indexName))))
        )
      )
    clientCertificate.map(request.withClientCertificate).getOrElse(request)
  }

  private def certificateWith(subject: String) =
    ClientCertificate(
      subjectDn = dn(subject),
      issuerDn = dn("CN=Corp Issuing CA,DC=corp"),
      subjectAlternativeNames = List.empty[SubjectAlternativeName]
    )

  private def dn(value: String) =
    DistinguishedName.from(value).getOrElse(throw new IllegalArgumentException(s"Cannot parse '$value'"))

}
