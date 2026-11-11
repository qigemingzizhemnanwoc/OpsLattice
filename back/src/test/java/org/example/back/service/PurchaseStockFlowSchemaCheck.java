package org.example.back.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC-01: explicit, read-only MySQL preflight. Run with -Dtest=PurchaseStockFlowSchemaCheck.
 * Intentionally not named *Test: ordinary unit tests must not require an external database.
 * Does not load application.properties, start Spring, create tables, or write business data.
 */
class PurchaseStockFlowSchemaCheck {

    @Test
    @DisplayName("TC-01: dedicated MySQL schema supports purchase stock flow persistence")
    void dedicatedDatabaseSupportsPurchaseStockFlows() throws Exception {
        String url = requiredEnvironment("PURCHASE_TEST_JDBC_URL");
        String database = requiredEnvironment("PURCHASE_TEST_DATABASE");
        String username = requiredEnvironment("PURCHASE_TEST_USERNAME");
        String password = System.getenv("PURCHASE_TEST_PASSWORD");
        assertNotNull(password,
                "TC-01 BLOCKED: set PURCHASE_TEST_PASSWORD locally; credentials are never read from application.properties");
        assertTrue(url.startsWith("jdbc:mysql://"), "TC-01 requires an explicit MySQL test connection");

        Properties credentials = new Properties();
        credentials.setProperty("user", username);
        credentials.setProperty("password", password);
        credentials.setProperty("connectTimeout", "5000");
        credentials.setProperty("socketTimeout", "10000");

        try (Connection connection = DriverManager.getConnection(url, credentials)) {
            connection.setReadOnly(true);
            assertEquals(database, connection.getCatalog(), "Connected database must match the explicit test database");
            assertTrue(connection.getMetaData().supportsTransactions(), "Database must support transactions");
            System.out.printf("TC-01 database=%s; version=%s; JDBC isolation=%d%n",
                    database, connection.getMetaData().getDatabaseProductVersion(), connection.getTransactionIsolation());

            for (String table : List.of("base_goods", "biz_purchase", "biz_stock_flow")) {
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
                    query.setString(1, database);
                    query.setString(2, table);
                    try (ResultSet rows = query.executeQuery()) {
                        assertTrue(rows.next(), "Missing required table: " + table);
                        assertEquals("InnoDB", rows.getString("ENGINE"), "Transactional engine required: " + table);
                    }
                }
                try (Statement query = connection.createStatement();
                     ResultSet rows = query.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
                    assertTrue(rows.next());
                    System.out.println(rows.getString(2));
                }
            }

            Map<String, Column> stockColumns = columns(connection, database, "base_goods");
            assertNotNull(stockColumns.get("stock"), "Missing base_goods.stock");
            assertEquals("int", stockColumns.get("stock").type());
            assertEquals("NO", stockColumns.get("stock").nullable());

            Map<String, Column> flowColumns = columns(connection, database, "biz_stock_flow");
            Map<String, String> requiredTypes = Map.ofEntries(
                    Map.entry("id", "bigint"), Map.entry("goods_id", "bigint"),
                    Map.entry("goods_name", "varchar"), Map.entry("change_quantity", "int"),
                    Map.entry("before_stock", "int"), Map.entry("after_stock", "int"),
                    Map.entry("biz_type", "varchar"), Map.entry("biz_id", "bigint"),
                    Map.entry("biz_no", "varchar"), Map.entry("operator_id", "bigint"),
                    Map.entry("operator_name", "varchar"), Map.entry("remark", "varchar"),
                    Map.entry("operation_time", "datetime"), Map.entry("create_time", "datetime"));
            for (Map.Entry<String, String> expected : requiredTypes.entrySet()) {
                Column column = flowColumns.get(expected.getKey());
                assertNotNull(column, "Missing biz_stock_flow." + expected.getKey());
                assertEquals(expected.getValue(), column.type(), "Review column mapping: " + expected.getKey());
            }
            assertEquals("PRI", flowColumns.get("id").key(), "Flow id must be a primary key");
            assertTrue(flowColumns.get("id").extra().contains("auto_increment"), "Flow id must be generated");

            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE
                    FROM information_schema.TRIGGERS
                    WHERE TRIGGER_SCHEMA = ?
                      AND EVENT_OBJECT_TABLE IN ('base_goods', 'biz_purchase', 'biz_stock_flow')
                    """)) {
                query.setString(1, database);
                try (ResultSet rows = query.executeQuery()) {
                    boolean requiresReview = false;
                    while (rows.next()) {
                        requiresReview = true;
                        System.out.printf("TC-01 trigger requires review: %s on %s%n",
                                rows.getString(1), rows.getString(2));
                    }
                    assertFalse(requiresReview,
                            "TC-01 BLOCKED: inspect reported triggers before deciding whether they duplicate inventory writes");
                }
            }
        }
    }

    private static Map<String, Column> columns(Connection connection, String database, String table) throws Exception {
        Map<String, Column> columns = new HashMap<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_KEY, EXTRA
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                """)) {
            query.setString(1, database);
            query.setString(2, table);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    columns.put(rows.getString("COLUMN_NAME"), new Column(rows.getString("DATA_TYPE"),
                            rows.getString("IS_NULLABLE"), rows.getString("COLUMN_KEY"), rows.getString("EXTRA")));
                }
            }
        }
        return columns;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(),
                "TC-01 BLOCKED: missing " + name + "; choose a dedicated test database before continuing");
        return value;
    }

    private record Column(String type, String nullable, String key, String extra) {
    }
}
