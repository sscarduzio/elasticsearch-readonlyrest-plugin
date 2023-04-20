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
package tech.beshu.ror.utils.misc;

import better.files.File;
import better.files.package$;
import scala.collection.immutable.Seq$;

import java.nio.file.Path;

public class Resources {

  public static Path getResourcePath(String resource) {
    return File.apply(Resources.class.getResource(resource).getPath(), Seq$.MODULE$.<String>newBuilder().result()).path();
  }

  public static String getResourceContent(String resource) {
    return File.apply(getResourcePath(resource)).contentAsString(package$.MODULE$.DefaultCharset());
  }

}
