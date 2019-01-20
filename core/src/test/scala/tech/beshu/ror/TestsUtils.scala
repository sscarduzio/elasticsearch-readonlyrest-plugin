package tech.beshu.ror

import java.time.Duration
import java.util.Base64

import org.scalatest.Matchers._
import tech.beshu.ror.acl.aDomain.{Group, Header, IndexName, LoggedUser}
import tech.beshu.ror.acl.aDomain.Header.Name
import tech.beshu.ror.acl.blocks.BlockContext

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

object TestsUtils {

  def basicAuthHeader(value: String): Header =
    Header(Name("Authorization"), "Basic " + Base64.getEncoder.encodeToString(value.getBytes))

  implicit def scalaFiniteDuration2JavaDuration(duration: FiniteDuration): Duration = Duration.ofMillis(duration.toMillis)

  trait BlockContextAssertion[SETTINGS] {

    def assertBlockContext(responseHeaders: Set[Header] = Set.empty,
                           contextHeaders: Set[Header] = Set.empty,
                           kibanaIndex: Option[IndexName] = None,
                           loggedUser: Option[LoggedUser] = None,
                           currentGroup: Option[Group] = None,
                           availableGroups: Set[Group] = Set.empty,
                           indices: Set[IndexName] = Set.empty)
                          (blockContext: BlockContext): Unit = {
      blockContext.responseHeaders should be(responseHeaders)
      blockContext.contextHeaders should be(contextHeaders)
      blockContext.kibanaIndex should be(kibanaIndex)
      blockContext.loggedUser should be(loggedUser)
      blockContext.currentGroup should be(currentGroup)
      blockContext.availableGroups should be(availableGroups)
      blockContext.indices should be(indices)
    }
  }

}
