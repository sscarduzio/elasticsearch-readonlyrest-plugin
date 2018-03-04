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
package tech.beshu.ror.commons.shims.request;

import java.util.Map;
import java.util.Set;

/**
 * Created by sscarduzio on 14/04/2017.
 */
public interface RequestInfoShim {

  String extractType();

  Integer getContentLength();

  Set<String> getExpandedIndices(Set<String> ixsSet);

  Set<String> extractIndexMetadata(String index);

  Long extractTaskId();

  Integer extractContentLength();

  String extractContent();

  String extractMethod();

  String extractURI();

  Set<String> extractIndices();

  Set<String> extractSnapshots();

  String extractAction();

  Map<String, String> extractRequestHeaders();

  String extractRemoteAddress();

  String extractLocalAddress();

  String extractId();

  void writeIndices(Set<String> newIndices);

  void writeResponseHeaders(Map<String, String> hMap);

  Set<String> extractAllIndicesAndAliases();

  boolean involvesIndices();

  boolean extractIsReadRequest();

  boolean extractIsCompositeRequest();
}
