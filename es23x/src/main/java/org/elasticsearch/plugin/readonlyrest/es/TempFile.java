package org.elasticsearch.plugin.readonlyrest.es;

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
