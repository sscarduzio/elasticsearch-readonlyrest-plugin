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

package tech.beshu.ror.utils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class TempFile {

  public static File newFile(String prefix, String suffix, String content) throws IOException {
    File tempFile = File.createTempFile(prefix, suffix);
    tempFile.deleteOnExit();
    BufferedWriter out = null;

    try {
      out = new BufferedWriter(new FileWriter(tempFile));
      out.write(content);
      out.flush();
    } finally {
      out.close();
    }

    return tempFile;
  }
}
