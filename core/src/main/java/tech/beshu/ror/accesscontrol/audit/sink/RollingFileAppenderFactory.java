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
package tech.beshu.ror.accesscontrol.audit.sink;

import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.rolling.RolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.TriggeringPolicy;
import org.apache.logging.log4j.core.config.Configuration;

import java.io.Serializable;

/**
 * Java shim that constructs a {@link RollingFileAppender} via its builder.
 * <p>
 * {@code RollingFileAppender.newBuilder()} uses an F-bounded recursive generic
 * ({@code B extends Builder<B>}) that Scala 3 cannot infer at the call site (defaults to
 * {@code Nothing}). Java resolves the bound without issue, so a single static factory
 * method here gives Scala a plain, non-generic entry point.
 */
public final class RollingFileAppenderFactory {

    private RollingFileAppenderFactory() {}

    public static RollingFileAppender create(
            String name,
            String fileName,
            String filePattern,
            Layout<? extends Serializable> layout,
            TriggeringPolicy policy,
            RolloverStrategy strategy,
            Configuration configuration) {
        return RollingFileAppender.newBuilder()
                .withFileName(fileName)
                .withFilePattern(filePattern)
                .setLayout(layout)
                .withPolicy(policy)
                .withStrategy(strategy)
                .setName(name)
                .setConfiguration(configuration)
                .build();
    }
}