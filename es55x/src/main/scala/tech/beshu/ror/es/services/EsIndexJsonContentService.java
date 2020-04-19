/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.es.services;

import com.google.common.collect.Maps;
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
import tech.beshu.ror.accesscontrol.domain;
import tech.beshu.ror.boot.Ror$;
import tech.beshu.ror.es.IndexJsonContentService;
import tech.beshu.ror.utils.ScalaJavaHelper$;

import java.util.Map;
import java.util.Optional;

@Singleton
public class EsIndexJsonContentService implements IndexJsonContentService {

  private final NodeClient client;

  @Inject
  public EsIndexJsonContentService(NodeClient client) {
    this.client = client;
  }

  @Override
  public Task<Either<ReadError, Map<String, ?>>> sourceOf(domain.IndexName index, String type, String id) {
    try {
      GetResponse response = client.get(client.prepareGet(index.value().toString(), type, id).request()).actionGet();
      Map<String, Object> source = Optional.ofNullable(response.getSourceAsMap()).orElse(Maps.newHashMap());
      return Task$.MODULE$
          .eval((Function0<Either<ReadError, Map<String, ?>>>) () -> Right$.MODULE$.apply(source))
          .executeOn(Ror$.MODULE$.blockingScheduler(), true);
    } catch (ResourceNotFoundException ex) {
      return Task$.MODULE$.now(Left$.MODULE$.apply(ContentNotFound$.MODULE$));
    } catch (Throwable t) {
      return Task$.MODULE$.now(Left$.MODULE$.apply(CannotReachContentSource$.MODULE$));
    }
  }

  @Override
  public Task<Either<WriteError, BoxedUnit>> saveContent(domain.IndexName index, String type, String id, Map<String, String> content) {
    CancelablePromise<Either<WriteError, BoxedUnit>> promise = ScalaJavaHelper$.MODULE$.newCancelablePromise();
    client
        .prepareBulk()
        .add(
            client
                .prepareIndex(index.value().toString(), type, id)
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
