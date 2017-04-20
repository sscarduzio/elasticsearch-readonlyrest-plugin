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

package org.elasticsearch.plugin.readonlyrest.acl.requestcontext;

import com.google.common.collect.Sets;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugin.readonlyrest.utils.ReflecUtils;

import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Set;

import static org.reflections.ReflectionUtils.getAllFields;

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
    logger.info("committing subrequest indices");
    Object originalSubReq = src.getOriginalSubRequest();

    if (originalSubReq instanceof MultiGetRequest.Item) {
      if (newIndices.isEmpty()) {
        throw new ElasticsearchException(
          "need to have one exactly one index to replace into a " + originalSubReq.getClass().getSimpleName());
      }
      ReflecUtils.setIndices(originalSubReq, newIndices, logger);
    }
    if (originalSubReq instanceof SearchRequest || originalSubReq instanceof DocWriteRequest<?>) {
      if (newIndices.isEmpty()) {
        throw new ElasticsearchException(
          "need to have at least one index to replace into a " + originalSubReq.getClass().getSimpleName());
      }
      ReflecUtils.setIndices(originalSubReq, newIndices, logger);
    }
  }

}
