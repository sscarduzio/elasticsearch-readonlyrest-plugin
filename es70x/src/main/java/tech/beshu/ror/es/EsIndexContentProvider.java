package tech.beshu.ror.es;

import monix.eval.Task$;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.node.NodeClient;
import scala.Function0;
import scala.util.Either;
import scala.util.Left$;
import scala.util.Right$;
import tech.beshu.ror.boot.Ror$;

public class EsIndexContentProvider implements IndexContentProvider {

  private final NodeClient client;

  public EsIndexContentProvider(NodeClient client) {
    this.client = client;
  }

  @Override
  public monix.eval.Task<Either<Error, String>> contentOf(String index, String type, String id) {
    try {
      GetResponse response = client.get(client.prepareGet(index, type, id).request()).actionGet();
      return Task$.MODULE$
          .eval((Function0<Either<Error, String>>) () -> Right$.MODULE$.apply(response.getSourceAsString()))
          .executeOn(Ror$.MODULE$.blockingScheduler(), true);
    } catch (ResourceNotFoundException ex) {
      return Task$.MODULE$.now(Left$.MODULE$.apply(ContentNotFound$.MODULE$));
    } catch (Throwable t) {
      return Task$.MODULE$.now(Left$.MODULE$.apply(CannotReachContentSource$.MODULE$));
    }
  }
}
