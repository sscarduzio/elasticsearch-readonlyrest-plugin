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
package tech.beshu.ror.unit.acl.factory.decoders.rules.auth

import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.LdapAuthorizationRule
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.{GroupId, GroupIdPattern}
import tech.beshu.ror.accesscontrol.domain.{GroupIdLike, GroupsLogic, GroupIds}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.SingletonLdapContainers
import tech.beshu.ror.utils.TestsUtils.unsafeNes
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class LdapAuthorizationRuleSettingsTests
  extends BaseRuleSettingsDecoderTest[LdapAuthorizationRule] {

  "An LdapAuthorizationRule" should {
    "be able to be loaded from config" when {
      "there is LDAP service with given name and groups are defined" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups: ["group3"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = rule => {
            rule.settings.groupsLogic should be(GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupId("group3")))))
            assertLdapAuthZServiceLayerTypes(rule.settings.ldap, withServerSideGroupsFiltering = false)
          }
        )
      }
      "there is LDAP service with given name and groups all_of rule is defined (legacy groups_and name)" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups_and: ["g*"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = rule => {
            rule.settings.groupsLogic should be(GroupsLogic.AllOf(GroupIds(UniqueNonEmptyList.of(GroupIdLike.from("g*")))))
            rule.settings.groupsLogic.asInstanceOf[GroupsLogic.AllOf].permittedGroupIds.ids.head shouldBe a[GroupIdPattern]
            assertLdapAuthZServiceLayerTypes(rule.settings.ldap, withServerSideGroupsFiltering = false)
          }
        )
      }
      "there is LDAP service with given name and groups all_of rule is defined (legacy groups_all_of name)" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups_all_of: ["g*"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = rule => {
            rule.settings.groupsLogic should be(GroupsLogic.AllOf(GroupIds(UniqueNonEmptyList.of(GroupIdLike.from("g*")))))
            rule.settings.groupsLogic.asInstanceOf[GroupsLogic.AllOf].permittedGroupIds.ids.head shouldBe a[GroupIdPattern]
            assertLdapAuthZServiceLayerTypes(rule.settings.ldap, withServerSideGroupsFiltering = false)
          }
        )
      }
      "there is LDAP service with given name and groups all_of rule is defined (in groups section)" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups:
               |        all_of: ["g*"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = rule => {
            rule.settings.groupsLogic should be(GroupsLogic.AllOf(GroupIds(UniqueNonEmptyList.of(GroupIdLike.from("g*")))))
            rule.settings.groupsLogic.asInstanceOf[GroupsLogic.AllOf].permittedGroupIds.ids.head shouldBe a[GroupIdPattern]
            assertLdapAuthZServiceLayerTypes(rule.settings.ldap, withServerSideGroupsFiltering = false)
          }
        )
      }
      "there is LDAP service with given name and groups any_of rule is defined (legacy groups_or name)" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups_or: ["group3", "group4*"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |    server_side_groups_filtering: false
               |""".stripMargin,
          assertion = rule => {
            rule.settings.groupsLogic should be (GroupsLogic.AnyOf(GroupIds(
              UniqueNonEmptyList.of(GroupId("group3"), GroupIdLike.from("group4*"))
            )))
            assertLdapAuthZServiceLayerTypes(rule.settings.ldap, withServerSideGroupsFiltering = false)
          }
        )
      }
      "there is LDAP service with given name and groups any_of rule is defined (legacy groups_any_of name)" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups_any_of: ["group3", "group4*"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |    server_side_groups_filtering: false
               |""".stripMargin,
          assertion = rule => {
            rule.settings.groupsLogic should be(GroupsLogic.AnyOf(GroupIds(
              UniqueNonEmptyList.of(GroupId("group3"), GroupIdLike.from("group4*"))
            )))
            assertLdapAuthZServiceLayerTypes(rule.settings.ldap, withServerSideGroupsFiltering = false)
          }
        )
      }
      "there is LDAP service with given name and groups any_of rule is defined (in groups section)" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups:
               |        any_of: ["group3", "group4*"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |    server_side_groups_filtering: false
               |""".stripMargin,
          assertion = rule => {
            rule.settings.groupsLogic should be(GroupsLogic.AnyOf(GroupIds(
              UniqueNonEmptyList.of(GroupId("group3"), GroupIdLike.from("group4*"))
            )))
            assertLdapAuthZServiceLayerTypes(rule.settings.ldap, withServerSideGroupsFiltering = false)
          }
        )
      }
      "there is LDAP service with given name and groups_not_any_of are defined" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups_not_any_of: ["group3", "group4*"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |    server_side_groups_filtering: false
               |""".stripMargin,
          assertion = rule => {
            rule.settings.groupsLogic should be(GroupsLogic.NotAnyOf(GroupIds(
              UniqueNonEmptyList.of(GroupId("group3"), GroupIdLike.from("group4*"))
            )))
            assertLdapAuthZServiceLayerTypes(rule.settings.ldap, withServerSideGroupsFiltering = false)
          }
        )
      }
      "there is LDAP service with given name and groups_not_any_of are defined (in groups section)" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups:
               |        not_any_of: ["group3", "group4*"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |    server_side_groups_filtering: false
               |""".stripMargin,
          assertion = rule => {
            rule.settings.groupsLogic should be(GroupsLogic.NotAnyOf(GroupIds(
              UniqueNonEmptyList.of(GroupId("group3"), GroupIdLike.from("group4*"))
            )))
            assertLdapAuthZServiceLayerTypes(rule.settings.ldap, withServerSideGroupsFiltering = false)
          }
        )
      }
      "there is LDAP service with given name and groups_not_all_of are defined" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups_not_all_of: ["group3", "group4*"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |    server_side_groups_filtering: false
               |""".stripMargin,
          assertion = rule => {
            rule.settings.groupsLogic should be(GroupsLogic.NotAllOf(GroupIds(
              UniqueNonEmptyList.of(GroupId("group3"), GroupIdLike.from("group4*"))
            )))
            assertLdapAuthZServiceLayerTypes(rule.settings.ldap, withServerSideGroupsFiltering = false)
          }
        )
      }
      "there is LDAP service with given name and groups_not_all_of are defined (in groups section)" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups:
               |        not_all_of: ["group3", "group4*"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |    server_side_groups_filtering: false
               |""".stripMargin,
          assertion = rule => {
            rule.settings.groupsLogic should be(GroupsLogic.NotAllOf(GroupIds(
              UniqueNonEmptyList.of(GroupId("group3"), GroupIdLike.from("group4*"))
            )))
            assertLdapAuthZServiceLayerTypes(rule.settings.ldap, withServerSideGroupsFiltering = false)
          }
        )
      }
      "there is LDAP service (with server-side groups filtering) with given name and groups_not_any_of are defined (legacy syntax)" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups_or: ["group1"]
               |      groups_not_any_of: ["group3", "group4*"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |    server_side_groups_filtering: true
               |""".stripMargin,
          assertion = rule => {
            rule.settings.groupsLogic should be(GroupsLogic.Combined(
              GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupId("group1")))),
              GroupsLogic.NotAnyOf(GroupIds(UniqueNonEmptyList.of(GroupId("group3"), GroupIdLike.from("group4*")))),
            ))
            assertLdapAuthZServiceLayerTypes(rule.settings.ldap, withServerSideGroupsFiltering = true)
          }
        )
      }
      "there is LDAP service (with server-side groups filtering) with given name and groups_not_any_of are defined (syntax with section)" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups:
               |        any_of: ["group1"]
               |        not_any_of: ["group3", "group4*"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |    server_side_groups_filtering: true
               |""".stripMargin,
          assertion = rule => {
            rule.settings.groupsLogic should be(GroupsLogic.Combined(
              GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupId("group1")))),
              GroupsLogic.NotAnyOf(GroupIds(UniqueNonEmptyList.of(GroupId("group3"), GroupIdLike.from("group4*")))),
            ))
            assertLdapAuthZServiceLayerTypes(rule.settings.ldap, withServerSideGroupsFiltering = true)
          }
        )
      }
      "there is LDAP service (with server-side groups filtering) with given name and groups_not_all_of are defined (legacy syntax)" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups_or: ["group1"]
               |      groups_not_all_of: ["group3", "group4*"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |    server_side_groups_filtering: true
               |""".stripMargin,
          assertion = rule => {
            rule.settings.groupsLogic should be(GroupsLogic.Combined(
              GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupId("group1")))),
              GroupsLogic.NotAllOf(GroupIds(UniqueNonEmptyList.of(GroupId("group3"), GroupIdLike.from("group4*")))),
            ))
            assertLdapAuthZServiceLayerTypes(rule.settings.ldap, withServerSideGroupsFiltering = true)
          }
        )
      }
      "there is LDAP service (with server-side groups filtering) with given name and groups_not_all_of are defined (syntax with section)" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups:
               |        any_of: ["group1"]
               |        not_all_of: ["group3", "group4*"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |    server_side_groups_filtering: true
               |""".stripMargin,
          assertion = rule => {
            rule.settings.groupsLogic should be(GroupsLogic.Combined(
              GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupId("group1")))),
              GroupsLogic.NotAllOf(GroupIds(UniqueNonEmptyList.of(GroupId("group3"), GroupIdLike.from("group4*")))),
            ))
            assertLdapAuthZServiceLayerTypes(rule.settings.ldap, withServerSideGroupsFiltering = true)
          }
        )
      }
      "there is LDAP service with server side groups filtering defined" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups_or: ["group3", "group4*"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |    server_side_groups_filtering: true
               |""".stripMargin,
          assertion = rule => {
            rule.settings.groupsLogic should be(GroupsLogic.AnyOf(GroupIds(
              UniqueNonEmptyList.of(GroupId("group3"), GroupIdLike.from("group4*"))
            )))
            assertLdapAuthZServiceLayerTypes(rule.settings.ldap, withServerSideGroupsFiltering = true)
          }
        )
      }
      "authorization LDAP rule can have caching declared at rule level" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups: ["group3"]
               |      cache_ttl: 10 sec
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = rule => {
            rule.settings.groupsLogic should be(GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupId("group3")))))
            assertLdapAuthZServiceLayerTypes(rule.settings.ldap, withServerSideGroupsFiltering = false, withRuleLevelCaching = true)
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no LDAP service with given name is defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap2"
               |      groups: ["group3"]
               |      cache_ttl_in_seconds: 10
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Cannot find LDAP service with name: ldap2")))
          }
        )
      }
      "groups are not defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("ldap_authorization rule requires to define 'groups_or'/'groups_and'/'groups_not_any_of'/'groups_not_all_of' arrays")))
          }
        )
      }
      "groups set is empty" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups: []
               |      cache_ttl_in_seconds: 10
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Non empty list of group IDs or/and patterns is required")))
          }
        )
      }
      "LDAP server-side groups filtering is enabled, but list of potentially permitted groups is not provided for groups_not_all_of" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups_not_all_of: ["group3"]
               |      cache_ttl_in_seconds: 10
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |    server_side_groups_filtering: true
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("It is not allowed to use groups_not_any_of and groups_not_all_of rule, when LDAP server-side groups filtering is enabled. Consider using a combined rule, which applies two rules at the same time: groups_or/groups_and together with groups_not_any_of/groups_not_all_of.")))
          }
        )
      }
      "LDAP server-side groups filtering is enabled, but list of potentially permitted groups is not provided for groups_not_any_of" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups_not_any_of: ["group3"]
               |      cache_ttl_in_seconds: 10
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |    server_side_groups_filtering: true
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("It is not allowed to use groups_not_any_of and groups_not_all_of rule, when LDAP server-side groups filtering is enabled. Consider using a combined rule, which applies two rules at the same time: groups_or/groups_and together with groups_not_any_of/groups_not_all_of.")))
          }
        )
      }
      "LDAP service can only handle authentication, not authorization" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups: ["group1"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Service: ldap1 cannot be used in 'ldap_authorization' rule")))
          }
        )
      }
    }
  }
}
