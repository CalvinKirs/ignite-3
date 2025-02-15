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

package org.apache.ignite.internal.sql.engine.planner;

import java.util.ArrayList;
import java.util.Collection;
import org.apache.ignite.internal.schema.NativeTypes;
import org.apache.ignite.internal.sql.engine.framework.TestBuilders;
import org.apache.ignite.internal.sql.engine.schema.IgniteSchema;
import org.apache.ignite.internal.sql.engine.trait.IgniteDistributions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Join with USING syntax tests.
 */
public class JoinWithUsingPlannerTest extends AbstractPlannerTest {
    /** Public schemas. */
    private static final Collection<IgniteSchema> schemas = new ArrayList<>();

    @BeforeAll
    public static void init() {
        IgniteSchema publicSchema = createSchema("PUBLIC",
                TestBuilders.table().name("T1")
                        .addColumn("EMPID", NativeTypes.INT32)
                        .addColumn("DEPTID", NativeTypes.INT32)
                        .addColumn("NAME", NativeTypes.STRING)
                        .distribution(IgniteDistributions.random())
                        .build(),
                TestBuilders.table().name("T2")
                        .addColumn("DEPTID", NativeTypes.INT32)
                        .addColumn("NAME", NativeTypes.STRING)
                        .addColumn("PARENTID", NativeTypes.INT32)
                        .distribution(IgniteDistributions.random())
                        .build()
        );

        IgniteSchema otherSchema = createSchema("OTHER",
                TestBuilders.table().name("T3")
                        .addColumn("EMPID", NativeTypes.INT32)
                        .addColumn("DEPTID", NativeTypes.INT32)
                        .addColumn("D", NativeTypes.DATE)
                        .distribution(IgniteDistributions.random())
                        .build()
        );

        schemas.add(publicSchema);
        schemas.add(otherSchema);
    }

    @Test
    public void testJoinWithUsing() throws Exception {
        // Join tables without aliases.
        assertPlan("SELECT * FROM T1 JOIN T2 USING (DEPTID)", schemas,
                hasColumns("DEPTID", "EMPID", "NAME", "NAME0", "PARENTID"));

        // Join tables with aliases.
        assertPlan("SELECT * FROM T1 AS A1 JOIN T2 AS A2 USING (DEPTID)", schemas,
                hasColumns("DEPTID", "EMPID", "NAME", "NAME0", "PARENTID"));

        // Join tables in different schemas.
        assertPlan("SELECT * FROM T1 JOIN OTHER.T3 USING (DEPTID)", schemas,
                hasColumns("DEPTID", "EMPID", "NAME", "EMPID0", "D"));

        // Join tables using two columns.
        assertPlan("SELECT * FROM T1 JOIN T2 USING (DEPTID, NAME)", schemas,
                hasColumns("DEPTID", "NAME", "EMPID", "PARENTID"));

        // Double join.
        assertPlan("SELECT * FROM T1 JOIN T2 USING (DEPTID) JOIN OTHER.T3 USING (EMPID) ", schemas,
                hasColumns("EMPID", "DEPTID", "NAME", "NAME0", "PARENTID", "DEPTID1", "D"));

        // Join table with subquery.
        assertPlan("SELECT * FROM T1 JOIN (SELECT * FROM T2) USING (DEPTID)", schemas,
                hasColumns("DEPTID", "EMPID", "NAME", "NAME0", "PARENTID"));

        // Join subqueries.
        assertPlan("SELECT * FROM (SELECT * FROM T1) AS T JOIN (SELECT * FROM T2) USING (DEPTID)", schemas,
                hasColumns("DEPTID", "EMPID", "NAME", "NAME0", "PARENTID"));

        // Select all tables columns.
        assertPlan("SELECT T1.*, T2.* FROM T1 JOIN T2 USING (DEPTID)", schemas,
                hasColumns("EMPID", "DEPTID", "NAME", "DEPTID0", "NAME0", "PARENTID"));

        // Select system columns and all table columns.
        assertPlan("SELECT T1.* FROM T1 JOIN T2 USING (DEPTID)", schemas,
                hasColumns("EMPID", "DEPTID", "NAME"));

        // System columns with "star".
        // TODO https://issues.apache.org/jira/browse/CALCITE-4923
        // For now we can't select system columns with "star", since when "star" is used, tables of join are rewrite to
        // subqueries without system columns.
        //assertPlan("SELECT *, T2._KEY FROM T1 JOIN T2 USING (DEPTID)", schemas,
        //    hasColumns("DEPTID", "EMPID", "NAME", "NAME0", "PARENTID", "_KEY"));
    }

    @Test
    public void testNaturalJoin() throws Exception {
        // Join tables without aliases.
        assertPlan("SELECT * FROM T1 NATURAL JOIN T2", schemas,
                hasColumns("DEPTID", "NAME", "EMPID", "PARENTID"));

        // Join tables with aliases.
        assertPlan("SELECT * FROM T1 AS A1 NATURAL JOIN T2 AS A2", schemas,
                hasColumns("DEPTID", "NAME", "EMPID", "PARENTID"));

        // Join tables in different schemas.
        assertPlan("SELECT * FROM T1 NATURAL JOIN OTHER.T3", schemas,
                hasColumns("EMPID", "DEPTID", "NAME", "D"));

        // Double join.
        // TODO https://issues.apache.org/jira/browse/CALCITE-4921
        //assertPlan("SELECT * FROM T1 NATURAL JOIN T2 NATURAL JOIN OTHER.T3", schemas,
        //    hasColumns("DEPTID", "EMPTID", "NAME", "PARENTID", "D"));

        // Join table with subquery.
        assertPlan("SELECT * FROM T1 NATURAL JOIN (SELECT * FROM T2)", schemas,
                hasColumns("DEPTID", "NAME", "EMPID", "PARENTID"));

        // Join subqueries.
        assertPlan("SELECT * FROM (SELECT * FROM T1) AS T NATURAL JOIN (SELECT * FROM T2)", schemas,
                hasColumns("DEPTID", "NAME", "EMPID", "PARENTID"));

        // Select explicit columns, system columns. Columns not ambiguous.
        // TODO https://issues.apache.org/jira/browse/CALCITE-4915
        //assertPlan("SELECT DEPTID, T1._KEY, T2.NAME FROM T1 NATURAL JOIN T2", schemas,
        //    hasColumns("DEPTID", "_KEY", "NAME"));

        // Select all tables columns.
        assertPlan("SELECT T1.*, T2.* FROM T1 NATURAL JOIN T2", schemas,
                hasColumns("EMPID", "DEPTID", "NAME", "DEPTID0", "NAME0", "PARENTID"));

        // Select system columns and all table columns.
        // TODO https://issues.apache.org/jira/browse/CALCITE-4923
        //assertPlan("SELECT T1.*, T2._KEY FROM T1 NATURAL JOIN T2", schemas,
        //    hasColumns("EMPID", "DEPTID", "NAME", "_KEY"));

        // System columns with "star".
        // TODO https://issues.apache.org/jira/browse/CALCITE-4923
        //assertPlan("SELECT *, T2._KEY FROM T1 NATURAL JOIN T2", schemas,
        //    hasColumns("DEPTID", "NAME", "EMPID", "PARENTID", "_KEY"));
    }
}
