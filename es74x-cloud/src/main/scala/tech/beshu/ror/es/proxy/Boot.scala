package tech.beshu.ror.es.proxy

import cats.data.EitherT
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import com.twitter.finagle.Http
import javassist.{ClassPool, CtField, CtNewMethod, Modifier}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.apache.http.HttpHost
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.client.{RestClient, RestHighLevelClient}
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.boot.StartingFailure
import tech.beshu.ror.es.proxy.es.{EsRestServiceSimulator, RestHighLevelClientAdapter}
import tech.beshu.ror.es.proxy.server.ProxyRestInterceptorService
import tech.beshu.ror.utils.ScalaOps._

object Boot extends IOApp with Logging {

  override def run(args: List[String]): IO[ExitCode] = {
    hackEsClasses()
    runServer.flatMap {
      case Right(closeHandler) =>
        val proxyApp = Resource.make(IO(closeHandler))(handler =>
          IO.suspend(handler())
        )
        proxyApp.use(_ => IO.never).as(ExitCode.Success)
      case Left(startingFailure) =>
        val errorMessage = s"Cannot start ReadonlyREST proxy: ${startingFailure.message}"
        startingFailure.throwable match {
          case Some(ex) => logger.error(errorMessage, ex)
          case None => logger.error(errorMessage)
        }
        IO.pure(ExitCode.Error)
    }
  }


  private def runServer: IO[Either[StartingFailure, CloseHandler]] = {
    val threadPool: ThreadPool = new ThreadPool(Settings.EMPTY)
    val esClient = createEsHighLevelClient()
    val result = for {
      simulator <- EitherT(EsRestServiceSimulator.create(new RestHighLevelClientAdapter(esClient), threadPool))
      server = Http.server.serve(":5000", new ProxyRestInterceptorService(simulator)) // todo: from config
    } yield () =>
      for {
        _ <- twitterFutureToIo(server.close())
        _ <- simulator.stop()
        _ <- Task(esClient.close())
        _ <- Task(threadPool.shutdownNow())
      } yield ()
    result.value
  }

  private def createEsHighLevelClient() = {
    new RestHighLevelClient(RestClient.builder(HttpHost.create("http://localhost:9201"))) // todo: from config
  }

  private type CloseHandler = () => IO[Unit]

  private def hackEsClasses(): Unit = {
    modifyEsRestNodeClient()
    modifyIndicesStatsResponse()
  }

  private def modifyEsRestNodeClient(): Unit = {
    val esRestNodeClientClass = ClassPool.getDefault.get("org.elasticsearch.client.support.AbstractClient")

    val oldAdminMethod = esRestNodeClientClass.getDeclaredMethod("admin")
    esRestNodeClientClass.removeMethod(oldAdminMethod)

    val adminClientClass = ClassPool.getDefault.get("org.elasticsearch.client.AdminClient")
    val newAdminField = new CtField(adminClientClass, "rorAdmin", esRestNodeClientClass)
    esRestNodeClientClass.addField(newAdminField)

    val newAdminMethod = CtNewMethod.make("public org.elasticsearch.client.AdminClient admin() { return this.rorAdmin; }", esRestNodeClientClass)
    esRestNodeClientClass.addMethod(newAdminMethod)

    esRestNodeClientClass.toClass
  }

  private def modifyIndicesStatsResponse(): Unit = {
    val esRestNodeClientClass = ClassPool.getDefault.get("org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse")

    val constructors = esRestNodeClientClass.getConstructors
    constructors.foreach(c => c.setModifiers(Modifier.PUBLIC))

    esRestNodeClientClass.toClass
  }
}
