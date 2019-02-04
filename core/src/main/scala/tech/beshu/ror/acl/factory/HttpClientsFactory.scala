package tech.beshu.ror.acl.factory

import java.util.concurrent.CopyOnWriteArrayList

import com.softwaremill.sttp.SttpBackend
import com.softwaremill.sttp.asynchttpclient.cats.AsyncHttpClientCatsBackend
import io.netty.util.HashedWheelTimer
import monix.eval.Task
import monix.execution.atomic.AtomicBoolean
import org.asynchttpclient.Dsl.asyncHttpClient
import org.asynchttpclient.netty.channel.DefaultChannelPool
import org.asynchttpclient.{AsyncHttpClient, DefaultAsyncHttpClientConfig}
import tech.beshu.ror.acl.factory.HttpClientsFactory.{Config, HttpClient}

import scala.collection.JavaConverters._

trait HttpClientsFactory {

  def create(config: Config): HttpClient
  def shutdown(): Unit
}

object HttpClientsFactory {
  type HttpClient = SttpBackend[Task, Nothing]
  final case class Config(validate: Boolean)
}

// todo: remove synchronized, use more sophisticated lock mechanism
class AsyncHttpClientsFactory extends HttpClientsFactory {

  private val existingClients = new CopyOnWriteArrayList[AsyncHttpClient]()
  private val isWorking = AtomicBoolean(true)

  override def create(config: Config): HttpClient = synchronized {
    if(isWorking.get()) {
      val asyncHttpClient = newAsyncHttpClient(config)
      existingClients.add(asyncHttpClient)
      AsyncHttpClientCatsBackend.usingClient(asyncHttpClient)
    } else {
      throw new IllegalStateException("Cannot create http client - factory was closed")
    }
  }

  override def shutdown(): Unit = synchronized {
    isWorking.set(false)
    existingClients.iterator().asScala.foreach(_.close())
  }

  private def newAsyncHttpClient(config: Config) = {
    val timer = new HashedWheelTimer
    val pool = new DefaultChannelPool(60000, -1, DefaultChannelPool.PoolLeaseStrategy.FIFO, timer, -1)
    asyncHttpClient {
      new DefaultAsyncHttpClientConfig.Builder()
        .setNettyTimer(timer)
        .setChannelPool(pool)
        .setUseInsecureTrustManager(!config.validate)
        .build()
    }
  }
}