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
package tech.beshu.ror.acl.definitions.ldaps.unboundid;

import com.google.common.collect.Lists;
import com.unboundid.ldap.sdk.AsyncRequestID;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchResultReference;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

class UnboundidSearchResultListener
  implements com.unboundid.ldap.sdk.AsyncSearchResultListener {

  private final CompletableFuture<List<SearchResultEntry>> futureToComplete;
  private final ArrayList<SearchResultEntry> searchResultEntries = Lists.newArrayList();

  UnboundidSearchResultListener(CompletableFuture<List<SearchResultEntry>> futureToComplete) {
    this.futureToComplete = futureToComplete;
  }

  @Override
  public void searchResultReceived(AsyncRequestID requestID, SearchResult searchResult) {
    if (ResultCode.SUCCESS.equals(searchResult.getResultCode())) {
      futureToComplete.complete(searchResultEntries);
    }
    else {
      futureToComplete.completeExceptionally(
        new LdapSearchError(searchResult.getResultCode(), searchResult.getResultString())
      );
    }
  }

  @Override
  public void searchEntryReturned(SearchResultEntry searchEntry) {
    searchResultEntries.add(searchEntry);
  }

  @Override
  public void searchReferenceReturned(SearchResultReference searchReference) {
    // nothing to do
  }
}