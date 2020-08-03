package tech.beshu.ror.utils.containers

trait UsingXpackSupport {
  def isUsingXpackSupport: Boolean
}
trait XpackSupport extends UsingXpackSupport {
  override final val isUsingXpackSupport: Boolean = true
}
trait NoXpackSupport extends UsingXpackSupport {
  override final val isUsingXpackSupport: Boolean = false
}
