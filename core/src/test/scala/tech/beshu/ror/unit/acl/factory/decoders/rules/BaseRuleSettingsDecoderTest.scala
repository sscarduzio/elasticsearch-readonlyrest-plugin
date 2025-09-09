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
package tech.beshu.ror.unit.acl.factory.decoders.rules

import cats.data.NonEmptyList
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Assertion, BeforeAndAfterAll, Inside, Suite}
import tech.beshu.ror.accesscontrol.EnabledAccessControlList
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.*
import tech.beshu.ror.accesscontrol.blocks.mocks.{MocksProvider, NoOpMocksProvider}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorSettingsIndex}
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.{Core, HttpClientsFactory, RawRorSettingsBasedCoreFactory}
import tech.beshu.ror.SystemContext
import tech.beshu.ror.mocks.MockHttpClientsFactory
import tech.beshu.ror.providers.*
import tech.beshu.ror.utils.TestsUtils.*

import scala.reflect.ClassTag

abstract class BaseRuleSettingsDecoderTest[T <: Rule : ClassTag] extends AnyWordSpec with BeforeAndAfterAll with Inside {
  this: Suite =>

  val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  override protected def afterAll(): Unit = {
    super.afterAll()
    ldapConnectionPoolProvider.close().runSyncUnsafe()
  }

  protected implicit def envVarsProvider: EnvVarsProvider = OsEnvVarsProvider

  protected def factory: RawRorSettingsBasedCoreFactory = {
    implicit val systemContext: SystemContext = new SystemContext(envVarsProvider = envVarsProvider)
    new RawRorSettingsBasedCoreFactory(defaultEsVersionForTests)
  }

  def assertDecodingSuccess(yaml: String,
                            assertion: T => Unit,
                            aFactory: RawRorSettingsBasedCoreFactory = factory,
                            httpClientsFactory: HttpClientsFactory = MockHttpClientsFactory,
                            mocksProvider: MocksProvider = NoOpMocksProvider): Unit = {
    inside(
      aFactory
        .createCoreFrom(
          rorSettingsFromUnsafe(yaml),
          RorSettingsIndex(IndexName.Full(".readonlyrest")),
          httpClientsFactory,
          ldapConnectionPoolProvider,
          mocksProvider
        )
        .runSyncUnsafe()
    ) { case Right(Core(acl: EnabledAccessControlList, _, _)) =>
      val rule = acl.blocks.head.rules.collect { case r: T => r }.headOption
        .getOrElse(throw new IllegalStateException("There was no expected rule in decoding result"))
      rule shouldBe a[T]
      assertion(rule)
    }
  }

  def assertDecodingFailure(yaml: String,
                            assertion: NonEmptyList[CoreCreationError] => Unit,
                            aFactory: RawRorSettingsBasedCoreFactory = factory,
                            httpClientsFactory: HttpClientsFactory = MockHttpClientsFactory,
                            mocksProvider: MocksProvider = NoOpMocksProvider): Unit = {
    inside(
      aFactory
        .createCoreFrom(
          rorSettingsFromUnsafe(yaml),
          RorSettingsIndex(IndexName.Full(".readonlyrest")),
          httpClientsFactory,
          ldapConnectionPoolProvider,
          mocksProvider
        )
        .runSyncUnsafe()
    ) { case Left(error) =>
      assertion(error)
    }
  }

  def assertLdapAuthNServiceLayerTypes(ldapService: LdapAuthenticationService,
                                       withRuleLevelCaching: Boolean = false): Assertion = {
    ldapService shouldBe a[LoggableLdapAuthenticationServiceDecorator]
    val loggableLdapService = ldapService.asInstanceOf[LoggableLdapAuthenticationServiceDecorator]
    val underlying = if (withRuleLevelCaching) {
      loggableLdapService.underlying shouldBe a[CacheableLdapAuthenticationServiceDecorator]
      val ruleLevelCachedLdapService = loggableLdapService.underlying.asInstanceOf[CacheableLdapAuthenticationServiceDecorator]
      ruleLevelCachedLdapService.underlying
    } else {
      loggableLdapService.underlying
    }
    underlying shouldBe a[CircuitBreakerLdapAuthenticationServiceDecorator]
    val circuitBreakerLdapAuthenticationService = underlying.asInstanceOf[CircuitBreakerLdapAuthenticationServiceDecorator]
    circuitBreakerLdapAuthenticationService.underlying shouldBe a[UnboundidLdapAuthenticationService]
    val unboundidLdapAuthenticationService = circuitBreakerLdapAuthenticationService.underlying.asInstanceOf[UnboundidLdapAuthenticationService]

    unboundidLdapAuthenticationService.ldapUsersService shouldBe a[CircuitBreakerLdapUsersServiceDecorator]
    val circuitBreakerLdapUsersService = unboundidLdapAuthenticationService.ldapUsersService.asInstanceOf[CircuitBreakerLdapUsersServiceDecorator]
    circuitBreakerLdapUsersService.underlying shouldBe a[UnboundidLdapUsersService]
  }

  def assertLdapAuthZServiceLayerTypes(ldapService: LdapAuthorizationService,
                                       withServerSideGroupsFiltering: Boolean,
                                       withRuleLevelCaching: Boolean = false): Assertion = {
    if(withServerSideGroupsFiltering) {
      assertLdapAuthZWithoutServerSideGroupsFilteringServiceLayerTypes(ldapService, withRuleLevelCaching)
    } else {
      assertLdapAuthZWithServerSideGroupsFilteringServiceLayerTypes(ldapService, withRuleLevelCaching)
    }
  }

  private def assertLdapAuthZWithoutServerSideGroupsFilteringServiceLayerTypes(ldapService: LdapAuthorizationService,
                                                                            withRuleLevelCaching: Boolean) = {
    ldapService shouldBe a[LoggableLdapAuthorizationService.WithGroupsFilteringDecorator]
    val loggableLdapService = ldapService.asInstanceOf[LoggableLdapAuthorizationService.WithGroupsFilteringDecorator]
    val underlying = if (withRuleLevelCaching) {
      loggableLdapService.underlying shouldBe a[CacheableLdapAuthorizationService.WithGroupsFilteringDecorator]
      val ruleLevelCachedLdapService = loggableLdapService.underlying.asInstanceOf[CacheableLdapAuthorizationService.WithGroupsFilteringDecorator]
      ruleLevelCachedLdapService.underlying
    } else {
      loggableLdapService.underlying
    }

    underlying shouldBe a[CircuitBreakerLdapAuthorizationService.WithGroupsFilteringDecorator]
    val circuitBreakerLdapAuthorizationService = underlying.asInstanceOf[CircuitBreakerLdapAuthorizationService.WithGroupsFilteringDecorator]
    val theBottomLdapService = circuitBreakerLdapAuthorizationService.underlying
    theBottomLdapService shouldBe an[UnboundidLdapDefaultGroupSearchAuthorizationServiceWithServerSideGroupsFiltering]
  }

  private def assertLdapAuthZWithServerSideGroupsFilteringServiceLayerTypes(ldapService: LdapAuthorizationService,
                                                                            withRuleLevelCaching: Boolean) = {
    ldapService shouldBe a[LoggableLdapAuthorizationService.WithoutGroupsFilteringDecorator]
    val loggableLdapService = ldapService.asInstanceOf[LoggableLdapAuthorizationService.WithoutGroupsFilteringDecorator]
    val underlying = if (withRuleLevelCaching) {
      loggableLdapService.underlying shouldBe a[CacheableLdapAuthorizationService.WithoutGroupsFilteringDecorator]
      val ruleLevelCachedLdapService = loggableLdapService.underlying.asInstanceOf[CacheableLdapAuthorizationService.WithoutGroupsFilteringDecorator]
      ruleLevelCachedLdapService.underlying
    } else {
      loggableLdapService.underlying
    }

    underlying shouldBe a[CircuitBreakerLdapAuthorizationService.WithoutGroupsFilteringDecorator]
    val circuitBreakerLdapAuthorizationService = underlying.asInstanceOf[CircuitBreakerLdapAuthorizationService.WithoutGroupsFilteringDecorator]
    val theBottomLdapService = circuitBreakerLdapAuthorizationService.underlying
    theBottomLdapService shouldBe an[UnboundidLdapDefaultGroupSearchAuthorizationServiceWithoutServerSideGroupsFiltering]
  }
}
