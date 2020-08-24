package tech.beshu.ror.es.request.context.types

import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext

final case class ReflectionBasedActionRequest(esContext: EsContext,
                                              aclContext: AccessControlStaticContext,
                                              clusterService: RorClusterService,
                                              threadPool: ThreadPool)
