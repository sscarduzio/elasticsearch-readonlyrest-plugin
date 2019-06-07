package tech.beshu.ror.es;

import monix.eval.Task;
import monix.eval.Task$;
import monix.execution.CancelablePromise;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.xcontent.XContentType;
import scala.Function0;
import scala.concurrent.Promise;
import scala.runtime.BoxedUnit;
import scala.util.Either;
import scala.util.Left$;
import scala.util.Right$;
import tech.beshu.ror.boot.Ror$;
import tech.beshu.ror.utils.ScalaJavaHelper$;

import java.util.Map;

@Singleton
public class EsIndexJsonContentProvider implements IndexJsonContentManager {

  private final NodeClient client;

  @Inject
  public EsIndexJsonContentProvider(NodeClient client) {
    this.client = client;
  }

  @Override
  public Task<Either<ReadError, Map<String, Object>>> sourceOf(String index, String type, String id) {
    try {
      GetResponse response = client.get(client.prepareGet(index, type, id).request()).actionGet();
      return Task$.MODULE$
          .eval((Function0<Either<ReadError, Map<String, Object>>>) () -> Right$.MODULE$.apply(response.getSourceAsMap()))
          .executeOn(Ror$.MODULE$.blockingScheduler(), true);
    } catch (ResourceNotFoundException ex) {
      return Task$.MODULE$.now(Left$.MODULE$.apply(ContentNotFound$.MODULE$));
    } catch (Throwable t) {
      return Task$.MODULE$.now(Left$.MODULE$.apply(CannotReachContentSource$.MODULE$));
    }
  }

  @Override
  public Task<Either<WriteError, BoxedUnit>> saveContent(String index, String type, String id, Map<String, String> content) {
    CancelablePromise<Either<WriteError, BoxedUnit>> promise = ScalaJavaHelper$.MODULE$.newCancelablePromise();
    client
        .prepareBulk()
        .add(
            client
                .prepareIndex(index, type, id)
                .setSource(content, XContentType.JSON)
                .request()
        )
        .execute(new PromiseActionListenerAdapter(promise));
    return Task.fromCancelablePromise(promise);
  }

  private static class PromiseActionListenerAdapter implements ActionListener<BulkResponse> {

    private final Promise<Either<WriteError, BoxedUnit>> promise;

    PromiseActionListenerAdapter(Promise<Either<WriteError, BoxedUnit>> promise) {
      this.promise = promise;
    }

    @Override
    public void onResponse(BulkResponse bulkItemResponses) {
      promise.success(Right$.MODULE$.apply(BoxedUnit.UNIT));
    }

    @Override
    public void onFailure(Exception e) {
      promise.success(Left$.MODULE$.apply(new CannotWriteToIndex(e)));
    }
  }
}
