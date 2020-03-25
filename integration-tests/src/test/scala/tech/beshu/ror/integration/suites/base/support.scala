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

import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest.Suite
import tech.beshu.ror.utils.containers.providers._
import tech.beshu.ror.utils.containers.{EsClusterProvider, EsContainerCreator}

object support {

  trait BaseIntegrationTest
    extends ForAllTestContainer
      with EsClusterProvider
      with RorConfigFileNameProvider {
    this: Suite with EsContainerCreator =>
  }

  trait SingleClientSupport extends SingleClient with SingleEsTarget
  trait MultipleClientsSupport extends MultipleClients with MultipleEsTargets
}
