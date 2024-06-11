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
package tech.beshu.ror.es.actions.rrconfig

import java.util
import cats.implicits._
import org.elasticsearch.action.FailedNodeException
import org.elasticsearch.action.support.ActionFilters
import org.elasticsearch.action.support.nodes.TransportNodesAction
import org.elasticsearch.cluster.node.DiscoveryNode
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.io.stream.{StreamInput, Writeable}
import org.elasticsearch.env.Environment
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.TransportService
import tech.beshu.ror.configuration.EnvironmentConfig
import tech.beshu.ror.configuration.loader.distributed.{NodeConfig, RawRorConfigLoadingAction, Timeout}
import tech.beshu.ror.configuration.loader._
import tech.beshu.ror.es.{EsEnv, IndexJsonContentService}
import tech.beshu.ror.es.services.EsIndexJsonContentService
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

import scala.annotation.unused
import scala.concurrent.duration._
import scala.language.postfixOps

class TransportRRConfigAction(actionName: String,
                              threadPool: ThreadPool,
                              clusterService: ClusterService,
                              transportService: TransportService,
                              actionFilters: ActionFilters,
                              env: Environment,
                              indexContentProvider: IndexJsonContentService,
                              request: Writeable.Reader[RRConfigsRequest],
                              nodeRequest: Writeable.Reader[RRConfigRequest],
                              nodeExecutor: String,
                              nodeResponseClass: Class[RRConfig],
                              @unused constructorDiscriminator: Unit)
  extends TransportNodesAction[RRConfigsRequest, RRConfigsResponse, RRConfigRequest, RRConfig](
    actionName,
    threadPool,
    clusterService,
    transportService,
    actionFilters,
    request,
    nodeRequest,
    nodeExecutor,
    nodeResponseClass
  ) {

  import tech.beshu.ror.boot.RorSchedulers.Implicits.rorRestApiScheduler

  private implicit val environmentConfig: EnvironmentConfig = EnvironmentConfig.default

  @Inject
  def this(actionName: String,
           threadPool: ThreadPool,
           clusterService: ClusterService,
           transportService: TransportService,
           actionFilters: ActionFilters,
           env: Environment,
           indexContentProvider: EsIndexJsonContentService,
          ) =
    this(
      RRConfigActionType.name,
      threadPool,
      clusterService,
      transportService,
      actionFilters,
      env,
      indexContentProvider,
      new RRConfigsRequest(_),
      new RRConfigRequest(_),
      ThreadPool.Names.GENERIC,
      classOf[RRConfig],
      ()
    )

  override def newResponse(request: RRConfigsRequest, responses: util.List[RRConfig], failures: util.List[FailedNodeException]): RRConfigsResponse = {
    new RRConfigsResponse(clusterService.getClusterName, responses, failures)
  }

  override def newNodeResponse(streamInput: StreamInput, discoveryNode: DiscoveryNode): RRConfig = {
    new RRConfig(streamInput)
  }

  override def newNodeRequest(request: RRConfigsRequest): RRConfigRequest =
    new RRConfigRequest(request.getNodeConfigRequest)

   private def loadConfig() = doPrivileged {
    RawRorConfigLoadingAction
      .load(EsEnv(env.configFile(), env.modulesFile()), indexContentProvider)
      .map(_.map(_.map(_.raw)))
  }

  override def nodeOperation(request: RRConfigRequest): RRConfig = {
    val nodeRequest = request.getNodeConfigRequest
    val nodeResponse =
      loadConfig()
        .runSyncUnsafe(toFiniteDuration(nodeRequest.timeout))
    new RRConfig(clusterService.localNode(), NodeConfig(nodeResponse))
  }

  private def toFiniteDuration(timeout: Timeout): FiniteDuration = timeout.nanos nanos

}


