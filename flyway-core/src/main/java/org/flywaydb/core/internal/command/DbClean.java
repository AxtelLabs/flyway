/*
 * Copyright 2010-2018 Boxfuse GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flywaydb.core.internal.command;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.FlywayCallback;
import org.flywaydb.core.internal.database.Connection;
import org.flywaydb.core.internal.database.Database;
import org.flywaydb.core.internal.database.Schema;
import org.flywaydb.core.internal.schemahistory.SchemaHistory;
import org.flywaydb.core.internal.util.StopWatch;
import org.flywaydb.core.internal.util.TimeFormat;
import org.flywaydb.core.internal.util.jdbc.TransactionTemplate;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Main workflow for cleaning the database.
 */
public class DbClean {
    private static final Log LOG = LogFactory.getLog(DbClean.class);

    /**
     * The connection to use.
     */
    private final Connection connection;

    /**
     * The schema history table.
     */
    private final SchemaHistory schemaHistory;

    /**
     * The schemas to clean.
     */
    private final Schema[] schemas;

    /**
     * The list of callbacks that fire before or after the clean task is executed.
     * You can add as many callbacks as you want.  These should be set on the Flyway class
     * by the end user as Flyway will set them automatically for you here.
     */
    private final List<FlywayCallback> callbacks;

    /**
     * Whether to disable clean.
     * <p>This is especially useful for production environments where running clean can be quite a career limiting move.</p>
     */
    private boolean cleanDisabled;

    /**
     * Creates a new database cleaner.
     *
     * @param database     The DB support for the connection.
     * @param schemaHistory The schema history table.
     * @param schemas       The schemas to clean.
     * @param callbacks     The list of callbacks that fire before or after the clean task is executed.
     * @param cleanDisabled Whether to disable clean.
     */
    public DbClean(Database database, SchemaHistory schemaHistory, Schema[] schemas,
                   List<FlywayCallback> callbacks, boolean cleanDisabled) {
        this.connection = database.getMainConnection();
        this.schemaHistory = schemaHistory;
        this.schemas = schemas;
        this.callbacks = callbacks;
        this.cleanDisabled = cleanDisabled;
    }

    /**
     * Cleans the schemas of all objects.
     *
     * @throws FlywayException when clean failed.
     */
    public void clean() throws FlywayException {
        if (cleanDisabled) {
            throw new FlywayException("Unable to execute clean as it has been disabled with the \"flyway.cleanDisabled\" property.");
        }
        try {
            for (final FlywayCallback callback : callbacks) {
                new TransactionTemplate(connection.getJdbcConnection()).execute(new Callable<Object>() {
                    @Override
                    public Object call() throws SQLException {
                        connection.changeCurrentSchemaTo(schemas[0]);
                        callback.beforeClean(connection.getJdbcConnection());
                        return null;
                    }
                });
            }

            connection.changeCurrentSchemaTo(schemas[0]);
            boolean dropSchemas = false;
            try {
                dropSchemas = schemaHistory.hasSchemasMarker();
            } catch (Exception e) {
                LOG.error("Error while checking whether the schemas should be dropped", e);
            }

            for (Schema schema : schemas) {
                if (!schema.exists()) {
                    LOG.warn("Unable to clean unknown schema: " + schema);
                    continue;
                }

                if (dropSchemas) {
                    dropSchema(schema);
                } else {
                    cleanSchema(schema);
                }
            }

            for (final FlywayCallback callback : callbacks) {
                new TransactionTemplate(connection.getJdbcConnection()).execute(new Callable<Object>() {
                    @Override
                    public Object call() throws SQLException {
                        connection.changeCurrentSchemaTo(schemas[0]);
                        callback.afterClean(connection.getJdbcConnection());
                        return null;
                    }
                });
            }
            schemaHistory.clearCache();
        } finally {
            connection.restoreCurrentSchema();
        }
    }

    /**
     * Drops this schema.
     *
     * @param schema The schema to drop.
     * @throws FlywayException when the drop failed.
     */
    private void dropSchema(final Schema schema) {
        LOG.debug("Dropping schema " + schema + " ...");
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        new TransactionTemplate(connection.getJdbcConnection()).execute(new Callable<Object>() {
            @Override
            public Void call() {
                schema.drop();
                return null;
            }
        });
        stopWatch.stop();
        LOG.info(String.format("Successfully dropped schema %s (execution time %s)",
                schema, TimeFormat.format(stopWatch.getTotalTimeMillis())));
    }

    /**
     * Cleans this schema of all objects.
     *
     * @param schema The schema to clean.
     * @throws FlywayException when clean failed.
     */
    private void cleanSchema(final Schema schema) {
        LOG.debug("Cleaning schema " + schema + " ...");
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        new TransactionTemplate(connection.getJdbcConnection()).execute(new Callable<Object>() {
            @Override
            public Void call() {
                schema.clean();
                return null;
            }
        });
        stopWatch.stop();
        LOG.info(String.format("Successfully cleaned schema %s (execution time %s)",
                schema, TimeFormat.format(stopWatch.getTotalTimeMillis())));
    }
}