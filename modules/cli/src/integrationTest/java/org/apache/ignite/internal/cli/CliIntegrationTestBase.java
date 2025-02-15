/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.cli;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.List;
import org.apache.ignite.Ignite;
import org.apache.ignite.internal.sql.engine.util.SqlTestUtils;
import org.apache.ignite.internal.testframework.IntegrationTestBase;
import org.apache.ignite.table.Table;
import org.apache.ignite.tx.Transaction;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.TestInstance;

/**
 * Integration test base. Setups ignite cluster per test class and provides useful fixtures and assertions.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@MicronautTest(rebuildContext = true)
public abstract class CliIntegrationTestBase extends IntegrationTestBase {
    /**
     * Template for node bootstrap config with Scalecube and Logical Topology settings for fast failure detection.
     */
    protected static final String FAST_FAILURE_DETECTION_NODE_BOOTSTRAP_CFG_TEMPLATE = "{\n"
            + "  network: {\n"
            + "    port: {},\n"
            + "    nodeFinder: {\n"
            + "      netClusterNodes: [ {} ]\n"
            + "    },\n"
            + "    membership: {\n"
            + "      membershipSyncInterval: 1000,\n"
            + "      failurePingInterval: 500,\n"
            + "      scaleCube: {\n"
            + "        membershipSuspicionMultiplier: 1,\n"
            + "        failurePingRequestMembers: 1,\n"
            + "        gossipInterval: 10\n"
            + "      },\n"
            + "    }\n"
            + "  },\n"
            + "  clientConnector: { port:{} }\n"
            + "}";


    protected static void createAndPopulateTable() {
        sql("CREATE TABLE person ( id INT PRIMARY KEY, name VARCHAR, salary DOUBLE)");

        int idx = 0;

        for (Object[] args : new Object[][]{
                {idx++, "Igor", 10d},
                {idx++, null, 15d},
                {idx++, "Ilya", 15d},
                {idx++, "Roma", 10d},
                {idx, "Roma", 10d}
        }) {
            sql("INSERT INTO person(id, name, salary) VALUES (?, ?, ?)", args);
        }
    }

    protected static List<List<Object>> sql(String sql, Object... args) {
        return sql(null, sql, args);
    }

    protected static List<List<Object>> sql(@Nullable Transaction tx, String sql, Object... args) {
        Ignite ignite = CLUSTER_NODES.get(0);

        return SqlTestUtils.sql(ignite, tx, sql, args);
    }

    protected static PrintWriter output(List<Character> buffer) {
        return new PrintWriter(new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) {
                for (int i = off; i < off + len; i++) {
                    buffer.add(cbuf[i]);
                }
            }

            @Override
            public void flush() {

            }

            @Override
            public void close() {

            }
        });
    }

    /** Drops all visible tables. */
    protected void dropAllTables() {
        for (Table t : CLUSTER_NODES.get(0).tables().tables()) {
            sql("DROP TABLE " + t.name());
        }
    }
}

