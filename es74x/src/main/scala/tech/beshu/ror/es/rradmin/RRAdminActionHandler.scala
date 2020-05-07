package tech.beshu.ror.es.rradmin

import java.nio.file.Path

import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock
import org.elasticsearch.ElasticsearchException
import org.elasticsearch.action.ActionListener
import tech.beshu.ror.adminapi.AdminRestApi
import tech.beshu.ror.boot.SchedulerPools
import tech.beshu.ror.configuration.{FileConfigLoader, IndexConfigManager, RorIndexNameConfiguration}
import tech.beshu.ror.es.IndexJsonContentService
import tech.beshu.ror.providers.JvmPropertiesProvider
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.utils.RorInstanceSupplier

import scala.concurrent.duration._
import scala.language.postfixOps

class RRAdminActionHandler(indexContentProvider: IndexJsonContentService,
                           esConfigFile: Path) {

  private implicit val adminRestApiScheduler: Scheduler = SchedulerPools.adminRestApiScheduler

  private val rorIndexNameConfig = RorIndexNameConfiguration
    .load(esConfigFile)
    .map(_.fold(e => throw new ElasticsearchException(e.message), identity))
    .runSyncUnsafe(10 seconds)(adminRestApiScheduler, CanBlock.permit)

  private val indexConfigManager = new IndexConfigManager(indexContentProvider, rorIndexNameConfig)
  private val fileConfigLoader = new FileConfigLoader(esConfigFile, JvmPropertiesProvider)

  def handle(request: RRAdminRequest, listener: ActionListener[RRAdminResponse]): Unit = {
    getApi match {
      case Some(api) => doPrivileged {
        api
          .call(request.getAdminRequest)
          .runAsync { response =>
            listener.onResponse(RRAdminResponse(response))
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
