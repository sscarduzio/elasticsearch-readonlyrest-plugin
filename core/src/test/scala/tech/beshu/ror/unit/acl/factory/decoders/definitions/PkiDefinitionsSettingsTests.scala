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
package tech.beshu.ror.unit.acl.factory.decoders.definitions

import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.definitions.PkiDef
import tech.beshu.ror.accesscontrol.blocks.definitions.PkiDef.{ExtractionSpec, SanType}
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.PkiDefinitionsDecoder
import tech.beshu.ror.utils.TestsUtils.nes

class PkiDefinitionsSettingsTests extends BaseDecoderTest(PkiDefinitionsDecoder.instance) {

  "A pkis definition" should {
    "be able to be loaded from config" when {
      "only a name is given" in {
        assertDecodingSuccess(
          yaml = """
                   |  pkis:
                   |  - name: corporate_pki
                   |""".stripMargin,
          assertion = { definitions =>
            val pki = singlePkiIn(definitions)
            pki.id should be(PkiDef.Name(nes("corporate_pki")))
            pki.scope should be(PkiDef.Scope.unrestricted)
            pki.usersExtraction should be(ExtractionSpec.SubjectDnAttribute(nes("CN")))
            pki.groupsExtraction should be(None)
          }
        )
      }
      "a user id attribute is given" in {
        assertDecodingSuccess(
          yaml = """
                   |  pkis:
                   |  - name: corporate_pki
                   |    users:
                   |      user_id_attribute: "UID"
                   |""".stripMargin,
          assertion = { definitions =>
            singlePkiIn(definitions).usersExtraction should be(ExtractionSpec.SubjectDnAttribute(nes("UID")))
          }
        )
      }
      "groups are read from a subject DN attribute" in {
        assertDecodingSuccess(
          yaml = """
                   |  pkis:
                   |  - name: corporate_pki
                   |    groups:
                   |      group_id_attribute: "OU"
                   |""".stripMargin,
          assertion = { definitions =>
            singlePkiIn(definitions).groupsExtraction should be(Some(ExtractionSpec.SubjectDnAttribute(nes("OU"))))
          }
        )
      }
      "users and groups are read using patterns" in {
        assertDecodingSuccess(
          yaml = """
                   |  pkis:
                   |  - name: legacy_pki
                   |    users:
                   |      mode: subject_dn_pattern
                   |      pattern: "^CN=([^,]+),OU=Service Accounts,.*$"
                   |    groups:
                   |      mode: subject_dn_pattern
                   |      pattern: "OU=grp-([^,]+)"
                   |""".stripMargin,
          assertion = { definitions =>
            val pki = singlePkiIn(definitions)
            inside(pki.usersExtraction) { case ExtractionSpec.SubjectDnPattern(pattern) =>
              pattern.value should be("^CN=([^,]+),OU=Service Accounts,.*$")
            }
            inside(pki.groupsExtraction) { case Some(ExtractionSpec.SubjectDnPattern(pattern)) =>
              pattern.value should be("OU=grp-([^,]+)")
            }
          }
        )
      }
      "the identity is an Active Directory user principal name" in {
        assertDecodingSuccess(
          yaml = """
                   |  pkis:
                   |  - name: ad_pki
                   |    users:
                   |      mode: san
                   |      san_type: upn
                   |""".stripMargin,
          assertion = { definitions =>
            singlePkiIn(definitions).usersExtraction should be(ExtractionSpec.San(SanType.Upn, None))
          }
        )
      }
      "the identity is selected from among several DNS subject alternative names" in {
        assertDecodingSuccess(
          yaml = """
                   |  pkis:
                   |  - name: host_pki
                   |    users:
                   |      mode: san
                   |      san_type: dns
                   |      pattern: "^(.+)\\.corp\\.example\\.com$"
                   |""".stripMargin,
          assertion = { definitions =>
            inside(singlePkiIn(definitions).usersExtraction) { case ExtractionSpec.San(sanType, Some(pattern)) =>
              sanType should be(SanType.Dns)
              pattern.value should be("^(.+)\\.corp\\.example\\.com$")
            }
          }
        )
      }
      "the accepted certificates are restricted by subject and issuer" in {
        assertDecodingSuccess(
          yaml = """
                   |  pkis:
                   |  - name: services_pki
                   |    subject_dn_base: "OU=Services,DC=corp,DC=example,DC=com"
                   |    issuer_dn: "CN=Corp Issuing CA,DC=corp,DC=example,DC=com"
                   |""".stripMargin,
          assertion = { definitions =>
            val scope = singlePkiIn(definitions).scope
            scope.subjectDnBase.map(_.value) should be(Some("OU=Services,DC=corp,DC=example,DC=com"))
            scope.issuerDn.map(_.value) should be(Some("CN=Corp Issuing CA,DC=corp,DC=example,DC=com"))
          }
        )
      }
      "several providers are defined" in {
        assertDecodingSuccess(
          yaml = """
                   |  pkis:
                   |  - name: services_pki
                   |    subject_dn_base: "OU=Services,DC=corp"
                   |  - name: employees_pki
                   |    subject_dn_base: "OU=People,DC=corp"
                   |""".stripMargin,
          assertion = { definitions =>
            definitions.items.map(_.id.value.value) should be(List("services_pki", "employees_pki"))
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "the section is declared but empty" in {
        assertDecodingFailure(
          yaml = """
                   |  pkis:
                   |""".stripMargin,
          assertion = { error =>
            error should be(DefinitionsLevelCreationError(Message("pkis declared, but no definition found")))
          }
        )
      }
      "two providers share a name" in {
        assertDecodingFailure(
          yaml = """
                   |  pkis:
                   |  - name: corporate_pki
                   |  - name: corporate_pki
                   |""".stripMargin,
          assertion = { error =>
            error should be(
              DefinitionsLevelCreationError(
                Message("pkis definitions must have unique identifiers. Duplicates: corporate_pki")
              )
            )
          }
        )
      }
      "the extraction mode is unknown" in {
        assertDecodingFailure(
          yaml = """
                   |  pkis:
                   |  - name: corporate_pki
                   |    users:
                   |      mode: whatever
                   |""".stripMargin,
          assertion = { error =>
            error should be(
              DefinitionsLevelCreationError(
                Message(
                  "Error for field 'mode': Unknown mode of PKI identity extraction: whatever. Supported modes are subject_dn_attribute, subject_dn_pattern, san"
                )
              )
            )
          }
        )
      }
      "group names are asked for" in {
        assertDecodingFailure(
          yaml = """
                   |  pkis:
                   |  - name: corporate_pki
                   |    groups:
                   |      group_id_attribute: "OU"
                   |      group_name_attribute: "description"
                   |""".stripMargin,
          assertion = { error =>
            error should be(
              DefinitionsLevelCreationError(
                Message(
                  "Group names (group_name_attribute) are not supported by PKI providers, because a certificate carries only one value per attribute [pki corporate_pki]"
                )
              )
            )
          }
        )
      }
      "the groups section doesn't say which attribute to read" in {
        assertDecodingFailure(
          yaml = """
                   |  pkis:
                   |  - name: corporate_pki
                   |    groups: {}
                   |""".stripMargin,
          assertion = { error =>
            error should be(
              DefinitionsLevelCreationError(
                Message("'group_id_attribute' is required by the 'subject_dn_attribute' mode [pki corporate_pki]")
              )
            )
          }
        )
      }
      "the pattern mode is used without a pattern" in {
        assertDecodingFailure(
          yaml = """
                   |  pkis:
                   |  - name: corporate_pki
                   |    users:
                   |      mode: subject_dn_pattern
                   |""".stripMargin,
          assertion = { error =>
            error should be(
              DefinitionsLevelCreationError(
                Message("'pattern' is required by the 'subject_dn_pattern' mode [pki corporate_pki]")
              )
            )
          }
        )
      }
      "the pattern cannot be compiled" in {
        assertDecodingFailure(
          yaml = """
                   |  pkis:
                   |  - name: corporate_pki
                   |    users:
                   |      mode: subject_dn_pattern
                   |      pattern: "CN=([a-z"
                   |""".stripMargin,
          assertion = { error =>
            error should be(
              DefinitionsLevelCreationError(
                Message("Cannot compile 'CN=([a-z' as a regular expression [pki corporate_pki]")
              )
            )
          }
        )
      }
      "the pattern defines no capture group" in {
        assertDecodingFailure(
          yaml = """
                   |  pkis:
                   |  - name: corporate_pki
                   |    users:
                   |      mode: subject_dn_pattern
                   |      pattern: "CN=[^,]+"
                   |""".stripMargin,
          assertion = { error =>
            error should be(
              DefinitionsLevelCreationError(
                Message(
                  "The 'users' pattern of the 'corporate_pki' PKI has to define exactly one capture group, but defines 0"
                )
              )
            )
          }
        )
      }
      "the pattern defines more than one capture group" in {
        assertDecodingFailure(
          yaml = """
                   |  pkis:
                   |  - name: corporate_pki
                   |    users:
                   |      mode: subject_dn_pattern
                   |      pattern: "CN=([^,]+),OU=([^,]+)"
                   |""".stripMargin,
          assertion = { error =>
            error should be(
              DefinitionsLevelCreationError(
                Message(
                  "The 'users' pattern of the 'corporate_pki' PKI has to define exactly one capture group, but defines 2"
                )
              )
            )
          }
        )
      }
      "the pattern can match an empty value" in {
        assertDecodingFailure(
          yaml = """
                   |  pkis:
                   |  - name: corporate_pki
                   |    users:
                   |      mode: subject_dn_pattern
                   |      pattern: "(.*)"
                   |""".stripMargin,
          assertion = { error =>
            error should be(
              DefinitionsLevelCreationError(
                Message(
                  "The 'users' pattern of the 'corporate_pki' PKI matches an empty value, so it cannot identify anything"
                )
              )
            )
          }
        )
      }
      "the san mode is used without a san type" in {
        assertDecodingFailure(
          yaml = """
                   |  pkis:
                   |  - name: corporate_pki
                   |    users:
                   |      mode: san
                   |""".stripMargin,
          assertion = { error =>
            error should be(
              DefinitionsLevelCreationError(Message("'san_type' is required by the 'san' mode [pki corporate_pki]"))
            )
          }
        )
      }
      "the san type is unknown" in {
        assertDecodingFailure(
          yaml = """
                   |  pkis:
                   |  - name: corporate_pki
                   |    users:
                   |      mode: san
                   |      san_type: whatever
                   |""".stripMargin,
          assertion = { error =>
            error should be(
              DefinitionsLevelCreationError(
                Message(
                  "Error for field 'san_type': Unknown SAN type: whatever. Supported types are dns, email, uri, ip, upn"
                )
              )
            )
          }
        )
      }
      "a scope constraint is not a distinguished name" in {
        assertDecodingFailure(
          yaml = """
                   |  pkis:
                   |  - name: corporate_pki
                   |    subject_dn_base: "not a distinguished name"
                   |""".stripMargin,
          assertion = { error =>
            error should be(
              DefinitionsLevelCreationError(
                Message(
                  "Error for field 'subject_dn_base': Cannot parse 'not a distinguished name' as a distinguished name"
                )
              )
            )
          }
        )
      }
    }
  }

  private def singlePkiIn(
      definitions: tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions[PkiDef]
  ) = {
    definitions.items should have size 1
    definitions.items.head
  }

}
