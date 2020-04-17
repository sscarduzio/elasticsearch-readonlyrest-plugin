package tech.beshu.ror.es.rrconfig

object NodeConfigRequestSerializer {

  import io.circe.generic.auto._
  import io.circe.parser
  import io.circe.syntax._

  def show(nodeConfigRequest: NodeConfigRequest): String = {
    nodeConfigRequest.asJson.noSpaces
  }

  def parse(str: String): NodeConfigRequest = {
    parser.decode[NodeConfigRequest](str).toTry.get
  }
}
