package tech.beshu.ror.es.rradmin

import monix.execution.Scheduler
import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.support.{ActionFilters, HandledTransportAction}
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.env.Environment
import org.elasticsearch.tasks.Task
import org.elasticsearch.transport.TransportService
import tech.beshu.ror.adminapi.AdminRestApi
import tech.beshu.ror.boot.SchedulerPools
import tech.beshu.ror.configuration.{FileConfigLoader, IndexConfigManager}
import tech.beshu.ror.es.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.es.{EsIndexJsonContentProvider, RorInstanceSupplier}
import tech.beshu.ror.providers.JvmPropertiesProvider

class TransportRRAdminAction(transportService: TransportService,
                             actionFilters: ActionFilters,
                             env: Environment,
                             indexContentProvider: EsIndexJsonContentProvider,
                             ignore: Unit) // hack!
  extends HandledTransportAction[RRAdminRequest, RRAdminResponse](
    RRAdminAction.name, transportService, actionFilters, () => new RRAdminRequest
  ) {

  @Inject
  def this(transportService: TransportService,
           actionFilters: ActionFilters,
           env: Environment,
           indexContentProvider: EsIndexJsonContentProvider) {
    this(transportService, actionFilters, env, indexContentProvider, ())
  }

  private implicit val adminRestApiScheduler: Scheduler = SchedulerPools.adminRestApiScheduler
  private val indexConfigManager = new IndexConfigManager(indexContentProvider)
  private val fileConfigLoader = new FileConfigLoader(env.configFile(), JvmPropertiesProvider)

  override def doExecute(task: Task, request: RRAdminRequest, listener: ActionListener[RRAdminResponse]): Unit = {
    getApi match {
      case Some(api) => doPrivileged {
        api
          .call(request.getAdminRequest)
          .runAsync { response =>
            listener.onResponse(new RRAdminResponse(response))
          }
      }
      case None =>
        listener.onResponse(new RRAdminResponse(AdminRestApi.AdminResponse.notAvailable))
    }
  }

  private def getApi =
    RorInstanceSupplier.get()
      .map(instance => new AdminRestApi(instance, indexConfigManager, fileConfigLoader))
}
