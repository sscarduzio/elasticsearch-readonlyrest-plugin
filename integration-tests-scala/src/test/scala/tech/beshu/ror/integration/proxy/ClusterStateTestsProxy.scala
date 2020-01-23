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
package tech.beshu.ror.integration.proxy

import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.utils.containers.{ReadonlyRestEsClusterContainerGeneric, RorProxyProvider}
import tech.beshu.ror.utils.elasticsearch.ClusterStateManager

class ClusterStateTestsProxy
  extends WordSpec
//    with BaseProxyTest
//    with ForAllTestContainer
    {

//  override val container = ReadonlyRestEsClusterContainerGeneric.createLocalClusterContainer(
//    RorProxyProvider.provideNodes(
//      clusterName = "ROR1",
//      esVersion = "7.5.1")
//  )
//
//  private lazy val adminClusterStateManager = new ClusterStateManager(container.nodesContainers.head.adminClient)
//
//  "/_cat/state should work as expected" in {
//    val response = adminClusterStateManager.healthCheck()
//
//    response.responseCode should be (200)
//  }
}
