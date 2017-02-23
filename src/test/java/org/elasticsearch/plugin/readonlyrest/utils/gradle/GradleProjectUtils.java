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
package org.elasticsearch.plugin.readonlyrest.utils.gradle;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

import java.io.File;
import java.util.Optional;

public class GradleProjectUtils {
    private final File projectDirectory;
    private final GradleProperties properties;

    public GradleProjectUtils(File projectDirectory) {
        this.projectDirectory = projectDirectory;
        this.properties = GradleProperties.create().get();
    }

    public Optional<File> assemble() {
        runTask(":assemble");
        File plugin = new File("build/distributions/" + pluginName());
        if(!plugin.exists()) return Optional.empty();
        return Optional.of(plugin);
    }

    private String pluginName() {
        return String.format("%s-%s_es%s.zip",
                properties.getProperty("pluginName"),
                properties.getProperty("pluginVersion"),
                properties.getProperty("esVersion"));
    }

    private void runTask(String task) {
        GradleConnector connector = GradleConnector.newConnector().forProjectDirectory(projectDirectory);
        ProjectConnection connect = null;
        try {
            connect = connector.connect();
            connect.newBuild().forTasks(task).run();
        } finally {
            if (connect != null) connect.close();
        }
    }
}
