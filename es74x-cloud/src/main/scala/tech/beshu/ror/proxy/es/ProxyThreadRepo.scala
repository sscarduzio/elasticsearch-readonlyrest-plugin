package tech.beshu.ror.proxy.es

object ProxyThreadRepo {
  private val threadLocalChannel = new ThreadLocal[ProxyRestChannel]

  def setRestChannel(restChannel: ProxyRestChannel): Unit = {
    threadLocalChannel.set(restChannel)
  }

  def getRestChannel: Option[ProxyRestChannel] = {
    val result = Option(threadLocalChannel.get)
    threadLocalChannel.remove()
    result
  }

  // todo: prove, that this works as expected (many, concurrent requests check)
  def clearRestChannel(): Unit = {
    threadLocalChannel.remove()
  }
}
