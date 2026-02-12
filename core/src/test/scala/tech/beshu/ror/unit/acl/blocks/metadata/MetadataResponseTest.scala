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
package tech.beshu.ror.unit.acl.blocks.metadata

import cats.data.NonEmptyList
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.BlockContext.UserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata.MetadataOrigin
import tech.beshu.ror.accesscontrol.blocks.metadata.{BlockMetadata, KibanaPolicy, MetadataResponse, UserMetadata}
import tech.beshu.ror.accesscontrol.domain.{Json as DomainJson, *}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.UserMetadataRequestContext.UserMetadataApiVersion
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*

class MetadataResponseTest extends AnyWordSpec with Matchers with MockFactory {

  "MetadataResponse" should {
    "filter kibana fields correctly based on license type" when {
      "Free license removes all premium fields" in {
        val metadata = createUserMetadataWithAllFields()
        val result = MetadataResponse.fromAsCirceJson(
          version = UserMetadataApiVersion.V2(RorKbnLicenseType.Free),
          userMetadata = metadata,
          currentGroupId = None,
          correlationId = CorrelationId(nes("test-id"))
        )

        result shouldBe circeJsonFrom(
          """{
            |  "type": "USER_WITHOUT_GROUPS",
            |  "correlation_id": "test-id",
            |  "username": "admin",
            |  "ror_origin": "jwt-issuer",
            |  "kibana": {
            |    "access": "admin",
            |    "index": ".kibana"
            |  }
            |}""".stripMargin)
      }
      "Pro license includes hidden_apps but not enterprise-only fields" in {
        val metadata = createUserMetadataWithAllFields()
        val result = MetadataResponse.fromAsCirceJson(
          version = UserMetadataApiVersion.V2(RorKbnLicenseType.Pro),
          userMetadata = metadata,
          currentGroupId = None,
          correlationId = CorrelationId(nes("test-id"))
        )

        result shouldBe circeJsonFrom(
          """{
            |  "type": "USER_WITHOUT_GROUPS",
            |  "correlation_id": "test-id",
            |  "username": "admin",
            |  "ror_origin": "jwt-issuer",
            |  "kibana": {
            |    "access": "admin",
            |    "index": ".kibana",
            |    "hidden_apps": ["Enterprise Search"]
            |  }
            |}""".stripMargin)
      }
      "Enterprise license with multitenancy disabled includes all fields" in {
        val metadata = createUserMetadataWithAllFields()
        val result = MetadataResponse.fromAsCirceJson(
          version = UserMetadataApiVersion.V2(RorKbnLicenseType.Enterprise(multiTenancyEnabled = false)),
          userMetadata = metadata,
          currentGroupId = None,
          correlationId = CorrelationId(nes("test-id"))
        )

        result shouldBe circeJsonFrom(
          """{
            |  "type": "USER_WITHOUT_GROUPS",
            |  "correlation_id": "test-id",
            |  "username": "admin",
            |  "ror_origin": "jwt-issuer",
            |  "kibana": {
            |    "access": "admin",
            |    "index": ".kibana",
            |    "template_index": ".kibana_template",
            |    "hidden_apps": ["Enterprise Search"],
            |    "metadata": {
            |      "role": "admin"
            |    }
            |  }
            |}""".stripMargin)
      }
      "Enterprise license with multitenancy enabled returns USER_WITH_GROUPS" in {
        val metadata = createUserMetadataWithGroups()
        val result = MetadataResponse.fromAsCirceJson(
          version = UserMetadataApiVersion.V2(RorKbnLicenseType.Enterprise(multiTenancyEnabled = true)),
          userMetadata = metadata,
          currentGroupId = None,
          correlationId = CorrelationId(nes("test-id"))
        )

        result shouldBe circeJsonFrom(
          """{
            |  "type": "USER_WITH_GROUPS",
            |  "correlation_id": "test-id",
            |  "groups": [
            |    {
            |      "group": {
            |        "id": "admins",
            |        "name": "Administrators"
            |      },
            |      "username": "admin",
            |      "ror_origin": "jwt-issuer",
            |      "kibana": {
            |        "access": "admin",
            |        "index": ".kibana",
            |        "template_index": ".kibana_template",
            |        "hidden_apps": ["Enterprise Search"],
            |        "metadata": {
            |          "role": "admin"
            |        }
            |      }
            |    },
            |    {
            |      "group": {
            |        "id": "users",
            |        "name": "Users"
            |      },
            |      "username": "admin",
            |      "kibana": {
            |        "access": "admin",
            |        "index": ".kibana_users",
            |        "template_index": ".kibana_users_template"
            |      }
            |    }
            |  ]
            |}""".stripMargin)
      }
    }
  }

  private def createUserMetadataWithAllFields(): UserMetadata.WithoutGroups = {
    UserMetadata.WithoutGroups(
      loggedUser = LoggedUser.DirectlyLoggedUser(User.Id(nes("admin"))),
      userOrigin = Some(UserOrigin(nes("jwt-issuer"))),
      kibanaPolicy = Some(KibanaPolicy(
        access = KibanaAccess.Admin,
        index = Some(kibanaIndexName(nes(".kibana"))),
        templateIndex = Some(kibanaIndexName(nes(".kibana_template"))),
        hiddenApps = Set(KibanaApp.FullNameKibanaApp(nes("Enterprise Search"))),
        allowedApiPaths = Set.empty,
        genericMetadata = Some(DomainJson.JsonTree.Object(Map("role" -> DomainJson.JsonTree.Value(DomainJson.JsonValue.StringValue("admin")))))
      )),
      metadataOrigin = MetadataOrigin(
        blockContext = dummyCtx
      )
    )
  }

  private def createUserMetadataWithGroups(): UserMetadata.WithGroups = {
    val group1 = UserMetadata.WithGroups.GroupMetadata(
      group = group("admins", "Administrators"),
      loggedUser = LoggedUser.DirectlyLoggedUser(User.Id(nes("admin"))),
      userOrigin = Some(UserOrigin(nes("jwt-issuer"))),
      kibanaPolicy = Some(KibanaPolicy(
        access = KibanaAccess.Admin,
        index = Some(kibanaIndexName(nes(".kibana"))),
        templateIndex = Some(kibanaIndexName(nes(".kibana_template"))),
        hiddenApps = Set(KibanaApp.FullNameKibanaApp(nes("Enterprise Search"))),
        allowedApiPaths = Set.empty,
        genericMetadata = Some(DomainJson.JsonTree.Object(Map("role" -> DomainJson.JsonTree.Value(DomainJson.JsonValue.StringValue("admin")))))
      )),
      metadataOrigin = MetadataOrigin(
        blockContext = dummyCtx
      )
    )

    val group2 = UserMetadata.WithGroups.GroupMetadata(
      group = group("users", "Users"),
      loggedUser = LoggedUser.DirectlyLoggedUser(User.Id(nes("admin"))),
      userOrigin = None,
      kibanaPolicy = Some(KibanaPolicy(
        access = KibanaAccess.Admin,
        index = Some(kibanaIndexName(nes(".kibana_users"))),
        templateIndex = Some(kibanaIndexName(nes(".kibana_users_template"))),
        hiddenApps = Set.empty,
        allowedApiPaths = Set.empty,
        genericMetadata = None
      )),
      metadataOrigin = MetadataOrigin(
        blockContext = dummyCtx
      )
    )

    UserMetadata.WithGroups(
      groupsMetadata = NonEmptyList.of(group1, group2)
    )
  }

  private lazy val dummyCtx = UserMetadataRequestBlockContext(
    block = mock[Block],
    requestContext = mock[RequestContext],
    blockMetadata = BlockMetadata.empty,
    responseHeaders = Set.empty,
    responseTransformations = Nil
  )

}
