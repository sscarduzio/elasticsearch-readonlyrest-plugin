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
package tech.beshu.ror.unit.acl

import cats.data.NonEmptyList
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.AccessControlList.UserMetadataRequestResult.{Allowed, Forbidden}
import tech.beshu.ror.accesscontrol.EnabledAccessControlList
import tech.beshu.ror.accesscontrol.EnabledAccessControlList.AccessControlListStaticContext
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.Block.Policy
import tech.beshu.ror.accesscontrol.blocks.BlockContext.UserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata.MetadataOrigin
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata.WithGroups.GroupMetadata
import tech.beshu.ror.accesscontrol.blocks.metadata.{BlockMetadata, UserMetadata}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.RorKbnLicenseType.Enterprise
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.request.{RestRequest, UserMetadataRequestContext}
import tech.beshu.ror.mocks.MockRuleFactory
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.NonEmptyListMap
import tech.beshu.ror.utils.TestsUtils.{*, given}
import tech.beshu.ror.utils.uniquelist.UniqueList

import scala.util.{Failure, Success, Try}

class EnabledAccessControlListTests extends AnyWordSpec with MockFactory with Inside with MockRuleFactory {

  import MockedBlockResult.*

  "The EnabledAccessControlList" when {
    "user metadata request is called" when {
      "ROR KBN license is enterprise" when {
        "all matched blocks have groups" should {
          "return allow with only allowed groups (case 1)" in {
            val acl = createAcl(
              block("b1", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g1"), group("g3")))),
              block("b2", Policy.Allow, result = Mismatched),
              block("b3", Policy.Forbid(), result = Matched(userId("u1"), UniqueList.of(group("g2"), group("g3")))),
              block("b4", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g3"), group("g4")))),
              block("b5", Policy.Forbid(), result = Mismatched),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(Enterprise(multiTenancyEnabled = true)))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) { case Allowed(UserMetadata.WithGroups(groupsMetadata)) =>
              groupsMetadata.keys.toList should be(GroupId("g1") :: GroupId("g3") :: GroupId("g4") :: Nil)

              groupsMetadata should contain(group("g1"), userId("u1"), "b1", Policy.Allow)
              groupsMetadata should contain(group("g3"), userId("u1"), "b1", Policy.Allow)
              groupsMetadata should contain(group("g4"), userId("u1"), "b4", Policy.Allow)
            }
          }
          "return allow with only allowed groups (case 2)" in {
            val acl = createAcl(
              block("b1", Policy.Forbid(), result = Matched(userId("u1"), UniqueList.of(group("g1"), group("g3")))),
              block("b2", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g2"), group("g3")))),
              block("b3", Policy.Allow, result = Mismatched),
              block("b4", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g4"), group("g5")))),
              block("b5", Policy.Forbid(), result = Mismatched),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(Enterprise(multiTenancyEnabled = true)))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) { case Allowed(UserMetadata.WithGroups(groupsMetadata)) =>
              groupsMetadata.keys.toList should be(GroupId("g2") :: GroupId("g4") :: GroupId("g5") :: Nil)

              groupsMetadata should contain(group("g2"), userId("u1"), "b2", Policy.Allow)
              groupsMetadata should contain(group("g4"), userId("u1"), "b4", Policy.Allow)
              groupsMetadata should contain(group("g5"), userId("u1"), "b4", Policy.Allow)
            }
          }
          "return forbidden when all matched groups are forbidden" in {
            val acl = createAcl(
              block(
                "b1",
                Policy.Forbid(Some("forbidden msg 1")),
                result = Matched(userId("u1"), UniqueList.of(group("g1"), group("g3")))
              ),
              block(
                "b2",
                Policy.Forbid(Some("forbidden msg 2")),
                result = Matched(userId("u1"), UniqueList.of(group("g2"), group("g3")))
              ),
              block("b3", Policy.Allow, result = Mismatched),
              block("b5", Policy.Forbid(Some("forbidden msg 3")), result = Mismatched),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(Enterprise(multiTenancyEnabled = true)))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) { case Forbidden(blockContext) =>
              blockContext.block.name should be(Block.Name("b1"))
              blockContext.block.policy should be(Policy.Forbid(Some("forbidden msg 1")))
            }
          }
        }
        "a single block grants access to many groups using a current-group-dependent rule" should {
          "resolve that rule independently for each group" in {
            val acl = createAclWith(showBasicAuthPrompt = false)(
              blockWithPerGroupKibanaIndex(
                "b1",
                Policy.Allow,
                userId("u1"),
                UniqueList.of(group("g1"), group("g2"), group("g3"))
              ),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(Enterprise(multiTenancyEnabled = true)))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) { case Allowed(UserMetadata.WithGroups(groupsMetadata)) =>
              groupsMetadata.keys.toList should be(GroupId("g1") :: GroupId("g2") :: GroupId("g3") :: Nil)

              groupsMetadata(GroupId("g1")).kibanaPolicy.map(_.index) should be(Some(kibanaIndexName("kibana_g1")))
              groupsMetadata(GroupId("g2")).kibanaPolicy.map(_.index) should be(Some(kibanaIndexName("kibana_g2")))
              groupsMetadata(GroupId("g3")).kibanaPolicy.map(_.index) should be(Some(kibanaIndexName("kibana_g3")))
            }
          }
        }
        "all matched blocks have no groups" should {
          "first matched block is an allow policy block" in {
            val acl = createAcl(
              block("b1", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b2", Policy.Allow, result = Mismatched),
              block("b3", Policy.Forbid(), result = Matched(userId("u1"), UniqueList.empty)),
              block("b4", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b5", Policy.Forbid(), result = Mismatched),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(Enterprise(multiTenancyEnabled = true)))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) {
              case Allowed(UserMetadata.WithoutGroups(loggedUser, None, None, MetadataOrigin(blockContext))) =>
                loggedUser.id should be(userId("u1"))
                blockContext.block.name should be(Block.Name("b1"))
                blockContext.block.policy should be(Policy.Allow)
            }
          }
          "first matched block is a forbid policy block" in {
            val acl = createAcl(
              block("b1", Policy.Forbid(Some("forbidden msg 1")), result = Matched(userId("u1"), UniqueList.empty)),
              block("b2", Policy.Allow, result = Mismatched),
              block("b3", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b4", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b5", Policy.Forbid(Some("forbidden msg23")), result = Mismatched),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(Enterprise(multiTenancyEnabled = true)))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) { case Forbidden(blockContext) =>
              blockContext.block.name should be(Block.Name("b1"))
              blockContext.block.policy should be(Policy.Forbid(Some("forbidden msg 1")))
            }
          }
        }
        "some matched blocks have groups, some - don't" should {
          "return allow with collected groups from all matched blocks with groups until a block without groups matches" in {
            val acl = createAcl(
              block("b1", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g1"), group("g3")))),
              block("b2", Policy.Allow, result = Mismatched),
              block("b3", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g2"), group("g3")))),
              block("b4", Policy.Forbid(), result = Matched(userId("u1"), UniqueList.empty)),
              block("b5", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b6", Policy.Forbid(), result = Matched(userId("u1"), UniqueList.of(group("g2"), group("g3")))),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(Enterprise(multiTenancyEnabled = true)))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) { case Allowed(UserMetadata.WithGroups(groupsMetadata)) =>
              groupsMetadata.keys.toList should be(GroupId("g1") :: GroupId("g3") :: GroupId("g2") :: Nil)

              groupsMetadata should contain(group("g1"), userId("u1"), "b1", Policy.Allow)
              groupsMetadata should contain(group("g3"), userId("u1"), "b1", Policy.Allow)
              groupsMetadata should contain(group("g2"), userId("u1"), "b3", Policy.Allow)
            }
          }
          "return forbidden when forbid block with groups matches before any block without groups" in {
            val acl = createAcl(
              block(
                "b1",
                Policy.Forbid(Some("forbidden msg 1")),
                result = Matched(userId("u1"), UniqueList.of(group("g1"), group("g2")))
              ),
              block("b2", Policy.Allow, result = Mismatched),
              block("b3", Policy.Forbid(Some("forbidden msg 2")), result = Matched(userId("u1"), UniqueList.empty)),
              block("b4", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b5", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g2"), group("g3")))),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(Enterprise(multiTenancyEnabled = true)))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) { case Forbidden(blockContext) =>
              blockContext.block.name should be(Block.Name("b1"))
              blockContext.block.policy should be(Policy.Forbid(Some("forbidden msg 1")))
            }
          }
          "return allow without groups when first matched block has no groups" in {
            val acl = createAcl(
              block("b1", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b2", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g1"), group("g2")))),
              block("b3", Policy.Forbid(), result = Matched(userId("u1"), UniqueList.of(group("g2"), group("g3")))),
              block("b4", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b5", Policy.Forbid(), result = Matched(userId("u1"), UniqueList.empty)),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(Enterprise(multiTenancyEnabled = true)))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) {
              case Allowed(UserMetadata.WithoutGroups(loggedUser, None, None, MetadataOrigin(blockContext))) =>
                loggedUser.id should be(userId("u1"))
                blockContext.block.name should be(Block.Name("b1"))
                blockContext.block.policy should be(Policy.Allow)
            }
          }
          "return forbidden when first matched block without groups is a forbid policy block" in {
            val acl = createAcl(
              block("b1", Policy.Forbid(Some("forbidden msg 1")), result = Matched(userId("u1"), UniqueList.empty)),
              block("b2", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g1"), group("g2")))),
              block(
                "b3",
                Policy.Forbid(Some("forbidden msg 2")),
                result = Matched(userId("u1"), UniqueList.of(group("g2"), group("g3")))
              ),
              block("b4", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b5", Policy.Forbid(Some("forbidden msg 3")), result = Matched(userId("u1"), UniqueList.empty)),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(Enterprise(multiTenancyEnabled = true)))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) { case Forbidden(blockContext) =>
              blockContext.block.name should be(Block.Name("b1"))
              blockContext.block.policy should be(Policy.Forbid(Some("forbidden msg 1")))
            }
          }
        }
      }

      def noTenancyHandlingBehavior(getLicenseType: => RorKbnLicenseType): Unit = {
        "all matched blocks have groups" should {
          "return allow with the first matched block (allow policy case)" in {
            val acl = createAcl(
              block("b1", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g1"), group("g3")))),
              block("b2", Policy.Allow, result = Mismatched),
              block("b3", Policy.Forbid(), result = Matched(userId("u1"), UniqueList.of(group("g2"), group("g3")))),
              block("b4", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g3"), group("g4")))),
              block("b5", Policy.Forbid(), result = Mismatched),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(getLicenseType))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) {
              case Allowed(UserMetadata.WithoutGroups(loggedUser, None, None, MetadataOrigin(blockContext))) =>
                loggedUser.id should be(userId("u1"))
                blockContext.block.name should be(Block.Name("b1"))
                blockContext.block.policy should be(Policy.Allow)
            }
          }
          "return forbidden when the first matched block is a forbid policy block" in {
            val acl = createAcl(
              block(
                "b1",
                Policy.Forbid(Some("forbidden msg 1")),
                result = Matched(userId("u1"), UniqueList.of(group("g1"), group("g3")))
              ),
              block("b2", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g2"), group("g3")))),
              block("b3", Policy.Allow, result = Mismatched),
              block("b4", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g4"), group("g5")))),
              block("b5", Policy.Forbid(Some("forbidden msg 2")), result = Mismatched),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(getLicenseType))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) { case Forbidden(blockContext) =>
              blockContext.block.name should be(Block.Name("b1"))
              blockContext.block.policy should be(Policy.Forbid(Some("forbidden msg 1")))
            }
          }
        }
        "all matched blocks have no groups" should {
          "first matched block is an allow policy block" in {
            val acl = createAcl(
              block("b1", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b2", Policy.Allow, result = Mismatched),
              block("b3", Policy.Forbid(), result = Matched(userId("u1"), UniqueList.empty)),
              block("b4", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b5", Policy.Forbid(), result = Mismatched),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(getLicenseType))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) {
              case Allowed(UserMetadata.WithoutGroups(loggedUser, None, None, MetadataOrigin(blockContext))) =>
                loggedUser.id should be(userId("u1"))
                blockContext.block.name should be(Block.Name("b1"))
                blockContext.block.policy should be(Policy.Allow)
            }
          }
          "first matched block is a forbid policy block" in {
            val acl = createAcl(
              block("b1", Policy.Forbid(Some("forbidden msg 1")), result = Matched(userId("u1"), UniqueList.empty)),
              block("b2", Policy.Allow, result = Mismatched),
              block("b3", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b4", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b5", Policy.Forbid(Some("forbidden msg23")), result = Mismatched),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(getLicenseType))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) { case Forbidden(blockContext) =>
              blockContext.block.name should be(Block.Name("b1"))
              blockContext.block.policy should be(Policy.Forbid(Some("forbidden msg 1")))
            }
          }
          "prefer the first allowed block that established a kibana policy over an earlier block without one" in {
            val acl = createAcl(
              block("b1", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              blockWithKibanaRule("b2", Policy.Allow, userId("u1"), kibanaAccessRule("kibana_access", KibanaAccess.RO)),
              blockWithKibanaRule(
                "b3",
                Policy.Allow,
                userId("u1"),
                kibanaIndexRule("kibana_index", kibanaIndexName("custom_kibana"))
              ),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(getLicenseType))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) {
              case Allowed(
                    UserMetadata.WithoutGroups(loggedUser, None, Some(kibanaPolicy), MetadataOrigin(blockContext))
                  ) =>
                loggedUser.id should be(userId("u1"))
                // b2 is the first allowed block with a kibana policy - it wins even though it has no
                // explicit kibana index and a later block (b3) declares one. Selection prioritises
                // "block established a kibana policy", not "block has an explicit kibana index".
                blockContext.block.name should be(Block.Name("b2"))
                kibanaPolicy.index should be(KibanaIndexName.default)
            }
          }
        }
        "some matched blocks have groups, some - don't" should {
          "return allow with the first block matched (case 1)" in {
            val acl = createAcl(
              block("b1", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g1"), group("g3")))),
              block("b2", Policy.Allow, result = Mismatched),
              block("b1", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g2"), group("g3")))),
              block("b3", Policy.Forbid(), result = Matched(userId("u1"), UniqueList.empty)),
              block("b4", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b5", Policy.Forbid(), result = Matched(userId("u1"), UniqueList.of(group("g2"), group("g3")))),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(getLicenseType))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) {
              case Allowed(UserMetadata.WithoutGroups(loggedUser, None, None, MetadataOrigin(blockContext))) =>
                loggedUser.id should be(userId("u1"))
                blockContext.block.name should be(Block.Name("b1"))
                blockContext.block.policy should be(Policy.Allow)
            }
          }
          "return allow with the first block matched (case 2)" in {
            val acl = createAcl(
              block("b1", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b2", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g1"), group("g2")))),
              block("b3", Policy.Forbid(), result = Matched(userId("u1"), UniqueList.of(group("g2"), group("g3")))),
              block("b4", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b5", Policy.Forbid(), result = Matched(userId("u1"), UniqueList.empty)),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(getLicenseType))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) {
              case Allowed(UserMetadata.WithoutGroups(loggedUser, None, None, MetadataOrigin(blockContext))) =>
                loggedUser.id should be(userId("u1"))
                blockContext.block.name should be(Block.Name("b1"))
                blockContext.block.policy should be(Policy.Allow)
            }
          }
          "return forbidden when the first matched block is forbidden (case 1)" in {
            val acl = createAcl(
              block(
                "b1",
                Policy.Forbid(Some("forbidden msg 1")),
                result = Matched(userId("u1"), UniqueList.of(group("g1"), group("g2")))
              ),
              block("b2", Policy.Allow, result = Mismatched),
              block("b3", Policy.Forbid(Some("forbidden msg 2")), result = Matched(userId("u1"), UniqueList.empty)),
              block("b4", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b5", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g2"), group("g3")))),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(getLicenseType))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) { case Forbidden(blockContext) =>
              blockContext.block.name should be(Block.Name("b1"))
              blockContext.block.policy should be(Policy.Forbid(Some("forbidden msg 1")))
            }
          }
          "return forbidden when the first matched block is forbidden (case 2)" in {
            val acl = createAcl(
              block("b1", Policy.Forbid(Some("forbidden msg 1")), result = Matched(userId("u1"), UniqueList.empty)),
              block("b2", Policy.Allow, result = Matched(userId("u1"), UniqueList.of(group("g1"), group("g2")))),
              block(
                "b3",
                Policy.Forbid(Some("forbidden msg 2")),
                result = Matched(userId("u1"), UniqueList.of(group("g2"), group("g3")))
              ),
              block("b4", Policy.Allow, result = Matched(userId("u1"), UniqueList.empty)),
              block("b5", Policy.Forbid(Some("forbidden msg 3")), result = Matched(userId("u1"), UniqueList.empty)),
            )

            val (userMetadataRequestResult, _) = acl
              .handleMetadataRequest(mockUserMetadataRequestContext(getLicenseType))
              .runSyncUnsafe()

            inside(userMetadataRequestResult) { case Forbidden(blockContext) =>
              blockContext.block.name should be(Block.Name("b1"))
              blockContext.block.policy should be(Policy.Forbid(Some("forbidden msg 1")))
            }
          }
        }
      }

      "ROR KBN license is enterprise (but with disabled tenancies)" should {
        behave like noTenancyHandlingBehavior(Enterprise(multiTenancyEnabled = false))
      }
      "ROR KBN license is pro" should {
        behave like noTenancyHandlingBehavior(RorKbnLicenseType.Pro)
      }
      "ROR KBN license is free" should {
        behave like noTenancyHandlingBehavior(RorKbnLicenseType.Free)
      }
    }
  }

  private def createAcl(blocks: Block*): EnabledAccessControlList =
    createAclWith(showBasicAuthPrompt = false)(blocks*)

  private def createAclWith(showBasicAuthPrompt: Boolean)(blocks: Block*) = {
    val blocksNel = NonEmptyList.fromListUnsafe(blocks.toList)
    new EnabledAccessControlList(
      blocksNel,
      new AccessControlListStaticContext(
        blocks = blocksNel,
        globalSettings = GlobalSettings(
          showBasicAuthPrompt = showBasicAuthPrompt,
          forbiddenRequestMessage = "Forbidden",
          flsEngine = FlsEngine.default,
          settingsIndex = RorSettingsIndex(IndexName.Full(".readonlyrest")),
          userIdCaseSensitivity = CaseSensitivity.Enabled,
          usersDefinitionDuplicateUsernamesValidationEnabled = true
        ),
        obfuscatedHeaders = Set.empty
      )
    )
  }

  private sealed trait MockedBlockResult

  private object MockedBlockResult {
    final case class Matched(userId: User.Id, groups: UniqueList[Group]) extends MockedBlockResult
    case object Mismatched extends MockedBlockResult
  }

  private def block(name: String, policy: Block.Policy, result: MockedBlockResult) = {
    new Block(
      name = Block.Name(name),
      policy = policy,
      rules = NonEmptyList.of(result match {
        case MockedBlockResult.Matched(userId, groups) => passingAuthRule("auth", userId, groups)
        case MockedBlockResult.Mismatched              => notPassingAuthRule("auth")
      }),
      audit = Block.Audit.Enabled(),
    )
  }

  private def blockWithKibanaRule(name: String, policy: Block.Policy, userId: User.Id, kibanaRule: Rule) = {
    new Block(
      name = Block.Name(name),
      policy = policy,
      audit = Block.Audit.Enabled(),
      rules = NonEmptyList.of(passingAuthRule("auth", userId, UniqueList.empty), kibanaRule),
    )
  }

  private def blockWithPerGroupKibanaIndex(
      name: String,
      policy: Block.Policy,
      userId: User.Id,
      groups: UniqueList[Group]
  ) = {
    new Block(
      name = Block.Name(name),
      policy = policy,
      rules = NonEmptyList.of(authorizationRule("groups", userId, groups), perGroupKibanaIndexRule("kibana")),
      audit = Block.Audit.Enabled(),
    )
  }

  private def mockUserMetadataRequestContext(licenseType: RorKbnLicenseType) = {
    mockRequestContext(UserMetadataRequestContext.Details(licenseType))
  }

  private def mockRequestContext(details: UserMetadataRequestContext.Details) = {
    val rc = mock[MockUserMetadataRequestContext]
    (rc.initialBlockContext _)
      .expects(*)
      .onCall { (block: Block) =>
        UserMetadataRequestBlockContext(block, rc, BlockMetadata.empty, Set.empty, List.empty)
      }
      .anyNumberOfTimes()

    (() => rc.details)
      .expects()
      .returning(details)
      .anyNumberOfTimes()

    val rr = mock[RestRequest]
    (() => rc.restRequest)
      .expects()
      .returning(rr)
      .anyNumberOfTimes()

    (() => rc.action)
      .expects()
      .returning(Action.RorAction.RorUserMetadataAction)
      .anyNumberOfTimes()
    rc
  }

  private trait MockUserMetadataRequestContext extends UserMetadataRequestContext {
    override type BLOCK_CONTEXT = UserMetadataRequestBlockContext
  }

  private def contain(group: Group, userId: User.Id, blockName: String, blockPolicy: Policy): GroupsMetadataMatcher =
    new GroupsMetadataMatcher(group, userId, blockName, blockPolicy)

  private class GroupsMetadataMatcher(group: Group, userId: User.Id, blockName: String, blockPolicy: Policy)
      extends Matcher[NonEmptyListMap[GroupId, GroupMetadata]] {

    override def apply(groupsMetadata: NonEmptyListMap[GroupId, GroupMetadata]): MatchResult = {
      val result = assert(groupsMetadata)
      val errorMessage = result match {
        case Failure(exception) => exception.getMessage
        case Success(_)         => ""
      }
      MatchResult(
        result.isSuccess,
        s"The groups metadata don't contain group [$group] along with metadata: user ID=[$userId] matched by block '$blockName' with policy '$blockPolicy'; $errorMessage",
        s"The groups metadata contain group [$group] along with metadata: user ID=[$userId] matched by block '$blockName' with policy '$blockPolicy'",
      )
    }

    private def assert(groupsMetadata: NonEmptyListMap[GroupId, GroupMetadata]) = Try {
      val metadata = groupsMetadata(group.id)
      val block = metadata.metadataOrigin.blockContext.block
      block.name should be(Block.Name(blockName))
      block.policy should be(blockPolicy)
      metadata.group should be(group)
      metadata.loggedUser.id should be(userId)
    }

  }

}
