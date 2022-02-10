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

import java.io.IOException
import java.util.function.{Function => JFunction}

import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.util.{SetOnce => LuceneSetOnce}
import org.elasticsearch.core.CheckedFunction
import org.elasticsearch.index.{IndexModule, IndexService}
import org.joor.Reflect.on
import tech.beshu.ror.es.dlsfls.RoleIndexSearcherWrapper
import tech.beshu.ror.es.utils.IndexModuleOps.ReaderWrapper
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

import scala.language.implicitConversions

class IndexModuleOps(indexModule: IndexModule) {

  def overwrite(readerWrapper: ReaderWrapper): Unit = {
    doPrivileged {
      on(indexModule).set("indexReaderWrapper", new LuceneSetOnce[ReaderWrapper]())
    }
    indexModule.setReaderWrapper(RoleIndexSearcherWrapper.instance)
  }
}

object IndexModuleOps {
  type ReaderWrapper = JFunction[IndexService, CheckedFunction[DirectoryReader, DirectoryReader, IOException]]

  implicit def toOps(indexModule: IndexModule): IndexModuleOps = new IndexModuleOps(indexModule)
}