package tech.beshu.ror.es.rrconfig

object NodeConfigSerializer {

  import io.circe.generic.auto._
  import io.circe.parser
  import io.circe.syntax._

  def show(nodeConfig: NodeConfig): String = {
    nodeConfig.asJson.noSpaces
  }

  def parse(str: String): NodeConfig = {
    parser.decode[NodeConfig](str).toTry.get
  }
}
