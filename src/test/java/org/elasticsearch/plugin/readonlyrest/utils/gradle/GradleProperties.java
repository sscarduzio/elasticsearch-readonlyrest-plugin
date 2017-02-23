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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

public class GradleProperties {
    private final Properties properties;

    private GradleProperties(Properties properties) {
        this.properties = properties;
    }

    public static Optional<GradleProperties> create() {
        try {
            Properties prop = new Properties();
            InputStream input = new FileInputStream("gradle.properties");
            prop.load(input);
            return Optional.of(new GradleProperties(prop));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    public String getProperty(String key) {
        return properties.getProperty(key);
    }
}
