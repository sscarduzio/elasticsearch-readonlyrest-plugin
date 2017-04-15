package org.elasticsearch.plugin.readonlyrest.acl.requestcontext;

import com.google.common.collect.Sets;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugin.readonlyrest.utils.ReflectionUtils;

import java.util.Set;

/**
 * Created by sscarduzio on 14/04/2017.
 */
public class SubRCTransactionalIndices extends Transactional<Set<String>> {

  private static Logger logger = Loggers.getLogger(SubRCTransactionalIndices.class);
  private final SubRequestContext src;

  SubRCTransactionalIndices(SubRequestContext src) {
    super("src-indices");
    this.src = src;
  }

  @Override
  public Set<String> initialize() {
    Object originalSubReq = src.getOriginalSubRequest();
    if (originalSubReq instanceof SearchRequest) {
      return Sets.newHashSet(((SearchRequest) originalSubReq).indices());
    }
    else if (originalSubReq instanceof MultiGetRequest.Item) {
      return Sets.newHashSet(((MultiGetRequest.Item) originalSubReq).indices());
    }
    else if (originalSubReq instanceof DocWriteRequest<?>) {
      return Sets.newHashSet(((DocWriteRequest<?>) originalSubReq).indices());
    }
    else {
      throw new RCUtils.RRContextException(
          "Cannot get indices from sub-request " + src.getClass().getSimpleName());
    }
  }

  @Override
  public Set<String> copy(Set<String> initial) {
    return Sets.newHashSet(initial);
  }

  @Override
  public void onCommit(Set<String> newIndices) {
    Object originalSubReq = src.getOriginalSubRequest();

    if (originalSubReq instanceof MultiGetRequest.Item) {
      ReflectionUtils.fieldChanger(MultiGetRequest.Item.class, "index", logger, src.getRequestContext(), (field) -> {
        if (newIndices.isEmpty()) {
          throw new ElasticsearchException(
              "need to have one exactly one index to replace into a " + originalSubReq.getClass().getSimpleName());
        }
        field.set(originalSubReq, newIndices.iterator().next());
        return null;
      });
    }
    if (originalSubReq instanceof SearchRequest || originalSubReq instanceof DocWriteRequest<?>) {
      ReflectionUtils.fieldChanger(originalSubReq.getClass(), "indices", logger, src.getRequestContext(), (field) -> {
        if (newIndices.isEmpty()) {
          throw new ElasticsearchException(
              "need to have at least one index to replace into a " + originalSubReq.getClass().getSimpleName());
        }
        field.set(originalSubReq, newIndices.iterator().next());
        return null;
      });
    }
  }

}
