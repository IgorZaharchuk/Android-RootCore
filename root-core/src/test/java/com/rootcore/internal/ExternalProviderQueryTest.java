package com.rootcore.internal;

import org.junit.Test;

import static org.junit.Assert.fail;

public final class ExternalProviderQueryTest {

    @Test
    public void acceptsPlainColumnNames() {
        ExternalProviderQuery.validateProjection(
                new String[] { "_id", "display_name", "media.title", "*" }
        );
    }

    @Test
    public void acceptsNullProjection() {
        ExternalProviderQuery.validateProjection(null);
    }

    @Test
    public void rejectsSqlExpressions() {
        assertRejected(new String[] { "name); DROP TABLE users; --" });
        assertRejected(new String[] { "COUNT(*)" });
        assertRejected(new String[] { "name AS alias" });
        assertRejected(new String[] { "name OR 1=1" });
        assertRejected(new String[] { "name/*comment*/" });
    }

    @Test
    public void rejectsDuplicateColumns() {
        assertRejected(new String[] { "_id", "_id" });
    }

    @Test
    public void rejectsTooManyColumns() {
        String[] columns = new String[65];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = "column_" + i;
        }
        assertRejected(columns);
    }

    private static void assertRejected(String[] projection) {
        try {
            ExternalProviderQuery.validateProjection(projection);
            fail("Projection should have been rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
