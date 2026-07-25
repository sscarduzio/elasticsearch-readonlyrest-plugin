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
package tech.beshu.ror.buildbase;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The one place that knows how to launch the wrapper from a child process on the current OS.
 * On Windows that means {@code gradlew.bat} wrapped in {@code cmd.exe /c} — CreateProcess
 * cannot execute batch files directly (error 193). Callers append their own gradle arguments.
 */
public final class GradlewCommand {

  private GradlewCommand() {}

  public static List<String> forHost(File rootDir) {
    boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
    List<String> cmd = new ArrayList<>();
    if (isWindows) {
      cmd.add("cmd.exe");
      cmd.add("/c");
      cmd.add(new File(rootDir, "gradlew.bat").getAbsolutePath());
    } else {
      cmd.add(new File(rootDir, "gradlew").getAbsolutePath());
    }
    return cmd;
  }
}
