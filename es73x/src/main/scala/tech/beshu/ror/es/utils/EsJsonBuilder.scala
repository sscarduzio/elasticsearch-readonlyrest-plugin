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
package tech.beshu.ror.es.utils

import org.elasticsearch.common.xcontent.XContentBuilder
import tech.beshu.ror.api.BaseEsJsonBuilder

class EsJsonBuilder(builder: XContentBuilder) extends BaseEsJsonBuilder {
  override def startObject(): Unit = builder.startObject
  override def endObject(): Unit = builder.endObject()
  override def startArray(name: String): Unit = builder.startArray(name)
  override def endArray(): Unit = builder.endArray()
  override def field(name: String, value: String): Unit = builder.field(name, value)
}
