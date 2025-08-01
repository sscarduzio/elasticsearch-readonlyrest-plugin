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

import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.util.SetOnce.AlreadySetException
import org.apache.lucene.util.{SetOnce => LuceneSetOnce}
import org.elasticsearch.core.CheckedFunction
import org.elasticsearch.index.{IndexModule, IndexService}
import org.joor.Reflect.on
import tech.beshu.ror.es.utils.IndexModuleOps.ReaderWrapper
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

import java.io.IOException
import java.util.function.{Function => JFunction}
import scala.annotation.tailrec
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

class IndexModuleOps(indexModule: IndexModule) {

  def overwrite(readerWrapper: ReaderWrapper): Unit = {
    doPrivileged {
      doOverwrite(readerWrapper)
    }
  }

  @tailrec
  private def doOverwrite(readerWrapper: ReaderWrapper, triesLeft: Int = 1): Unit = {
    Try {
      indexModule.setReaderWrapper(readerWrapper)
    } match {
      case Success(()) => ()
      case Failure(_: AlreadySetException) if triesLeft > 0 =>
        on(indexModule).set("indexReaderWrapper", new LuceneSetOnce[ReaderWrapper]())
        doOverwrite(readerWrapper, triesLeft - 1)
      case Failure(ex) =>
        throw ex
    }
  }
}

object IndexModuleOps {
  type ReaderWrapper = JFunction[IndexService, CheckedFunction[DirectoryReader, DirectoryReader, IOException]]

  implicit def toOps(indexModule: IndexModule): IndexModuleOps = new IndexModuleOps(indexModule)

}
