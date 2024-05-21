package com.clickhouse.client.query;


import com.clickhouse.client.BaseIntegrationTest;
import com.clickhouse.client.ClickHouseClient;
import com.clickhouse.client.ClickHouseConfig;
import com.clickhouse.client.ClickHouseNode;
import com.clickhouse.client.ClickHouseNodeSelector;
import com.clickhouse.client.ClickHouseProtocol;
import com.clickhouse.client.ClickHouseRequest;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.OperationStatistics;
import com.clickhouse.client.api.Protocol;
import com.clickhouse.client.api.data_formats.ClickHouseBinaryFormatReader;
import com.clickhouse.client.api.data_formats.NativeFormatReader;
import com.clickhouse.client.api.data_formats.RowBinaryFormatReader;
import com.clickhouse.client.api.data_formats.RowBinaryWithNamesAndTypesFormatReader;
import com.clickhouse.client.api.data_formats.RowBinaryWithNamesFormatReader;
import com.clickhouse.client.api.metadata.TableSchema;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;
import com.clickhouse.data.ClickHouseFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.BaseStream;

public class QueryTests extends BaseIntegrationTest {

    private final static Random RANDOM = new Random();

    private Client client;

    @BeforeMethod(groups = {"integration"})
    public void setUp() {
        ClickHouseNode node = getServer(ClickHouseProtocol.HTTP);
        client = new Client.Builder()
                .addEndpoint(Protocol.HTTP, node.getHost(), node.getPort())
                .addUsername("default")
                .addPassword("")
                .build();

        delayForProfiler(0);
        System.out.println("Real port: " + node.getPort());
    }

    private static void delayForProfiler(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Test(groups = {"integration"})
    public void testSimpleQueryWithTSV() {
        prepareSimpleDataSet();

        QuerySettings settings = new QuerySettings()
                .setFormat(ClickHouseFormat.TabSeparated.name());

        Future<QueryResponse> response = client.query("SELECT * FROM " + DATASET_TABLE,
                Collections.emptyMap(), settings);

        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(response.get().getInputStream()));
            String line = null;
            while ((line = br.readLine()) != null) {
                System.out.println(line);
            }
        } catch (InterruptedException | ExecutionException e) {
            Assert.fail("failed to get response", e);
        } catch (IOException e) {
            Assert.fail("failed to read response", e);

        }
    }

    @Test(groups = {"integration"}, enabled = false)
    public void testQueryJSONWith64BitIntegers() throws ExecutionException, InterruptedException {
        // won't work because format settings are set thru separate statement.
        prepareSimpleDataSet();
        List<QuerySettings> settingsList = Arrays.asList(
                new QuerySettings()
                        .setFormat(ClickHouseFormat.JSONEachRow.name())
                        .setSetting("format_json_quote_64bit_integers", "true"),

                new QuerySettings().setFormat(ClickHouseFormat.JSONEachRow.name()));
        List<Boolean> expected = Arrays.asList(true, false);

        Iterator<Boolean> expectedIterator = expected.iterator();
        for (QuerySettings settings : settingsList) {
            Future<QueryResponse> response = client.query("SELECT * FROM " + DATASET_TABLE, null, settings);
            QueryResponse queryResponse = response.get();
            ArrayList<JsonNode> records = new ArrayList<>();
            final ObjectMapper objectMapper = new ObjectMapper();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(queryResponse.getInputStream()))) {
                String line = null;
                while ((line = br.readLine()) != null) {
                    System.out.println(line);
                    records.add(objectMapper.readTree(line));
                    Assert.assertEquals(records.get(0).get("param4").isTextual(), expectedIterator.next());
                }
            } catch (IOException e) {
                Assert.fail("failed to read response", e);
            }
        }
    }

    @DataProvider(name = "rowBinaryFormats")
    ClickHouseFormat[] getRowBinaryFormats() {
        return new ClickHouseFormat[]{
                ClickHouseFormat.RowBinaryWithNamesAndTypes,
                ClickHouseFormat.Native,
                ClickHouseFormat.RowBinaryWithNames,
                ClickHouseFormat.RowBinary
        };
    }

    @Test(groups = {"integration"}, dataProvider = "rowBinaryFormats")
    public void testRowBinaryQueries(ClickHouseFormat format)
            throws ExecutionException, InterruptedException {
        final int rows = 1;
        // TODO: replace with dataset with all primitive types of data
        // TODO: reusing same table name may lead to a conflict in tests?

        List<Map<String, Object>> data = prepareDataSet(DATASET_TABLE + "_" + format.name(), DATASET_COLUMNS,
                DATASET_VALUE_GENERATORS, rows);
        QuerySettings settings = new QuerySettings().setFormat(format.name());
        Future<QueryResponse> response = client.query("SELECT * FROM " + DATASET_TABLE + "_" + format.name(), null, settings);
        QueryResponse queryResponse = response.get();

        Map<String, Object> record = new HashMap<>();
        TableSchema tableSchema = client.getTableSchema(DATASET_TABLE + "_" + format.name());
        ClickHouseBinaryFormatReader reader = createBinaryFormatReader(queryResponse, settings, tableSchema);

        Iterator<Map<String, Object>> dataIterator = data.iterator();
        try {
            while (dataIterator.hasNext()) {
                Map<String, Object> expectedRecord = dataIterator.next();
                reader.next();
                reader.copyRecord(record);
                Assert.assertEquals(record, expectedRecord);
            }
        } catch (IOException e) {
            Assert.fail("failed to read response", e);
        }
    }

    private static ClickHouseBinaryFormatReader createBinaryFormatReader(QueryResponse response, QuerySettings settings,
                                                                         TableSchema schema) {
        ClickHouseBinaryFormatReader reader = null;
        switch (response.getFormat()) {
            case Native:
                reader = new NativeFormatReader(response.getInputStream(), settings);
                break;
            case RowBinaryWithNamesAndTypes:
                reader = new RowBinaryWithNamesAndTypesFormatReader(response.getInputStream(), settings);
                break;
            case RowBinaryWithNames:
                reader = new RowBinaryWithNamesFormatReader(response.getInputStream(), settings, schema);
                break;
            case RowBinary:
                reader = new RowBinaryFormatReader(response.getInputStream(), settings, schema);
                break;
            default:
                Assert.fail("unsupported format: " + response.getFormat().name());
        }
        return reader;
    }

    @Test(groups = {"integration"})
    public void testBinaryStreamReader() throws Exception {
        final String table = "dynamic_schema_test_table";
        List<Map<String, Object>> data = prepareDataSet(table, DATASET_COLUMNS,
                DATASET_VALUE_GENERATORS, 10);
        QuerySettings settings = new QuerySettings().setFormat(ClickHouseFormat.RowBinaryWithNamesAndTypes.name());
        Future<QueryResponse> response = client.query("SELECT col1, col3, hostname() as host FROM " + table, null, settings);
        QueryResponse queryResponse = response.get();

        TableSchema schema = new TableSchema();
        schema.addColumn("col1", "UInt32");
        schema.addColumn("col3", "String");
        schema.addColumn("host", "String");
        ClickHouseBinaryFormatReader reader = createBinaryFormatReader(queryResponse, settings, schema);
        while (reader.hasNext()) {
            Assert.assertTrue(reader.next());
            String hostName = reader.readValue("host");
            Long col1 = reader.readValue("col1");
            String col3 = reader.readValue("col3");

            System.out.println("host: " + hostName + ", col1: " + col1 + ", col3: " + col3);

            Assert.assertEquals(reader.readValue(1), col1);
            Assert.assertEquals(reader.readValue(2), col3);
            Assert.assertEquals(reader.readValue(3), hostName);
        }

        Assert.assertFalse(reader.next());
    }

    @Test(groups = {"integration"})
    public void testRowStreamReader() throws Exception {
        final String table = "dynamic_schema_row_test_table";
        final int rows = 10;
        List<Map<String, Object>> data = prepareDataSet(table, DATASET_COLUMNS, DATASET_VALUE_GENERATORS, rows);
        QuerySettings settings = new QuerySettings().setFormat(ClickHouseFormat.RowBinaryWithNamesAndTypes.name());
        Future<QueryResponse> response = client.query("SELECT col1, col3, hostname() as host FROM " + table, null, settings);

        QueryResponse queryResponse = response.get();
        TableSchema schema = new TableSchema();
        schema.addColumn("col1", "UInt32");
        schema.addColumn("col3", "String");
        schema.addColumn("host", "String");
        ClickHouseBinaryFormatReader reader = new RowBinaryWithNamesAndTypesFormatReader(queryResponse.getInputStream(), settings);

        Map<String, Object> record = new HashMap<>();
        for (int i = 0; i < rows; i++) {
            record.clear();
            reader.next();
            reader.copyRecord(record);
        }
    }

    private final static List<String> ARRAY_COLUMNS = Arrays.asList(
            "col1 Array(UInt32)",
            "col2 Array(Array(Int32))"
    );

    private final static List<Function<String, Object>> ARRAY_VALUE_GENERATORS = Arrays.asList(
            c ->
                    RANDOM.ints(10, 0, 100),
            c -> {
                List<List<Integer>> values = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    values.add(Arrays.asList(1, 2, 3));
                }
                return values;
            }
    );


    @Test
    public void testArrayValues() throws Exception {
        final String table = "array_values_test_table";
        final int rows = 1;
        List<Map<String, Object>> data = prepareDataSet(table, ARRAY_COLUMNS, ARRAY_VALUE_GENERATORS, rows);

        QuerySettings settings = new QuerySettings().setFormat(ClickHouseFormat.RowBinaryWithNamesAndTypes.name());
        Future<QueryResponse> response = client.query("SELECT * FROM " + table, null, settings);
        TableSchema schema = client.getTableSchema(table);

        QueryResponse queryResponse = response.get();
        ClickHouseBinaryFormatReader reader = createBinaryFormatReader(queryResponse, settings, schema);

        Assert.assertTrue(reader.next());
        Map<String, Object> record = new HashMap<>();
        reader.copyRecord(record);
        long[] col1Values = reader.getLongArray("col1");
        System.out.println("col1: " + Arrays.toString(col1Values));
        System.out.println("Record: " + record);
    }

    private final static List<String> MAP_COLUMNS = Arrays.asList(
            "col1 Map(String, Int8)",
            "col2 Map(String, String)"
    );

    private final static List<Function<String, Object>> MAP_VALUE_GENERATORS = Arrays.asList(
            c -> {
                Map<String, Byte> values = new HashMap<>();
                values.put("key1", (byte) 1);
                values.put("key2", (byte) 2);
                values.put("key3", (byte) 3);
                return values;
            },

            c -> {
                Map<String, String> values = new HashMap<>();
                values.put("key1", "value1");
                values.put("key2", "value2");
                values.put("key3", "value3");
                return values;
            }
    );


    @Test
    public void testMapValues() throws Exception {
        final String table = "map_values_test_table";
        final int rows = 1;
        List<Map<String, Object>> data = prepareDataSet(table, MAP_COLUMNS, MAP_VALUE_GENERATORS, rows);

        QuerySettings settings = new QuerySettings().setFormat(ClickHouseFormat.RowBinaryWithNamesAndTypes.name());
        Future<QueryResponse> response = client.query("SELECT * FROM " + table, null, settings);
        TableSchema schema = client.getTableSchema(table);

        QueryResponse queryResponse = response.get();
        ClickHouseBinaryFormatReader reader = createBinaryFormatReader(queryResponse, settings, schema);

        Assert.assertTrue(reader.next());
        Map<String, Object> record = new HashMap<>();
        reader.copyRecord(record);
//        System.out.println("col1: " + Arrays.toString(col1Values));
        System.out.println("Record: " + record);
    }


    @Test(groups = {"integration"})
    public void testQueryExceptionHandling() {

    }

    @Test(groups = {"integration"})
    public void testQueryMetrics() throws Exception {
        prepareDataSet(DATASET_TABLE, DATASET_COLUMNS, DATASET_VALUE_GENERATORS, 10);

        QuerySettings settings = new QuerySettings()
                .setFormat(ClickHouseFormat.TabSeparated.name());

        QueryResponse response = client.query("SELECT * FROM " + DATASET_TABLE + " LIMIT 3",
                Collections.emptyMap(), settings).get();

        // Stats should be available after the query is done
        OperationStatistics stats = response.getOperationStatistics();
        OperationStatistics.ServerStatistics serverStats = stats.serverStatistics;
        System.out.println("Server stats: " + serverStats);
        System.out.println("Client stats: " + stats.clientStatistics);

        Assert.assertTrue(serverStats.numBytesRead > 0);
        Assert.assertEquals(serverStats.numBytesWritten, 0);
        Assert.assertEquals(serverStats.numRowsRead, 10); // 10 rows in the table
        Assert.assertEquals(serverStats.numRowsWritten, 0);
        Assert.assertEquals(serverStats.totalRowsToRead, 3);
        Assert.assertEquals(serverStats.resultRows, 3);
        Assert.assertTrue(serverStats.elapsedTime > 1);

        StringBuilder insertStmtBuilder = new StringBuilder();
        insertStmtBuilder.append("INSERT INTO default.").append(DATASET_TABLE).append(" VALUES ");
        final int rowsToInsert = 5;
        for (int i = 0; i < rowsToInsert; i++) {
            insertStmtBuilder.append("(");
            Map<String, Object> values = writeValuesRow(insertStmtBuilder, DATASET_COLUMNS, DATASET_VALUE_GENERATORS);
            insertStmtBuilder.setLength(insertStmtBuilder.length() - 2);
            insertStmtBuilder.append("), ");
        }
        response = client.query(insertStmtBuilder.toString(),
                Collections.emptyMap(), settings).get();

        serverStats = response.getOperationStatistics().serverStatistics;
        System.out.println("Server stats: " + serverStats);
        System.out.println("Client stats: " + stats.clientStatistics);

        // Server stats: ServerStatistics{"numRowsRead"=10, "numRowsWritten"=10, "totalRowsToRead"=0, "numBytesRead"=651, "numBytesWritten"=651}
        Assert.assertTrue(serverStats.numBytesRead > 0);
        Assert.assertTrue(serverStats.numBytesWritten > 0);
        Assert.assertEquals(serverStats.numRowsRead, rowsToInsert); // 10 rows in the table
        Assert.assertEquals(serverStats.numRowsWritten, rowsToInsert); // 10 rows inserted
        Assert.assertEquals(serverStats.totalRowsToRead, 0);
        Assert.assertEquals(serverStats.resultRows, rowsToInsert);
        Assert.assertTrue(serverStats.elapsedTime > 1);
        Assert.assertTrue(stats.clientStatistics.getElapsedTime("query") > 0);
    }

    private final static List<String> DATASET_COLUMNS = Arrays.asList(
            "col1 UInt32",
            "col2 Int32",
            "col3 String",
            "col4 Int64",
            "col5 String"
    );

    private final static List<Function<String, Object>> DATASET_VALUE_GENERATORS = Arrays.asList(
            c -> Long.valueOf(RANDOM.nextInt(Integer.MAX_VALUE)),
            c -> RANDOM.nextInt(Integer.MAX_VALUE),
            c -> "value_" + RANDOM.nextInt(Integer.MAX_VALUE),
            c -> Long.valueOf(RANDOM.nextInt(Integer.MAX_VALUE)),
            c -> "value_" + RANDOM.nextInt(Integer.MAX_VALUE)
    );

    private final static String DATASET_TABLE = "query_test_table";

    private void prepareSimpleDataSet() {
        prepareDataSet(DATASET_TABLE, DATASET_COLUMNS, DATASET_VALUE_GENERATORS, 1);
    }

    private List<Map<String, Object>> prepareDataSet(String table, List<String> columns, List<Function<String, Object>> valueGenerators,
                                                     int rows) {
        List<Map<String, Object>> data = new ArrayList<>(rows);
        try (
                ClickHouseClient client = ClickHouseClient.builder().config(new ClickHouseConfig())
                        .nodeSelector(ClickHouseNodeSelector.of(ClickHouseProtocol.HTTP))
                        .build()) {
            // Drop table
            ClickHouseRequest<?> request = client.read(getServer(ClickHouseProtocol.HTTP))
                    .query("DROP TABLE IF EXISTS default." + table);
            request.executeAndWait();



            // Create table
            StringBuilder createStmtBuilder = new StringBuilder();
            createStmtBuilder.append("CREATE TABLE IF NOT EXISTS default.").append(table).append(" (");
            for (String column : columns) {
                createStmtBuilder.append(column).append(", ");
            }
            createStmtBuilder.setLength(createStmtBuilder.length() - 2);
            createStmtBuilder.append(") ENGINE = MergeTree ORDER BY tuple()");
            request = client.read(getServer(ClickHouseProtocol.HTTP))
                    .query(createStmtBuilder.toString());
            request.executeAndWait();


            // Insert data
            StringBuilder insertStmtBuilder = new StringBuilder();
            insertStmtBuilder.append("INSERT INTO default.").append(table).append(" VALUES ");
            for (int i = 0; i < rows; i++) {
                insertStmtBuilder.append("(");
                Map<String, Object> values = writeValuesRow(insertStmtBuilder, columns, valueGenerators);
                insertStmtBuilder.setLength(insertStmtBuilder.length() - 2);
                insertStmtBuilder.append("), ");
                data.add(values);
            }
            System.out.println("Insert statement: " + insertStmtBuilder);
            request = client.write(getServer(ClickHouseProtocol.HTTP))
                    .query(insertStmtBuilder.toString());
            request.executeAndWait();
        } catch (Exception e) {
            Assert.fail("failed to prepare data set", e);
        }
        return data;
    }

    private Map<String, Object> writeValuesRow(StringBuilder insertStmtBuilder, List<String> columns, List<Function<String, Object>> valueGenerators ) {
        Map<String, Object> values = new HashMap<>();
        Iterator<String> columnIterator = columns.iterator();
        for (Function<String, Object> valueGenerator : valueGenerators) {
            Object value = valueGenerator.apply(null);
            if (value instanceof String) {
                insertStmtBuilder.append('\'').append(value).append('\'').append(", ");
            } else if (value instanceof BaseStream<?, ?>) {
                insertStmtBuilder.append('[');
                BaseStream stream = ((BaseStream<?, ?>) value);
                for (Iterator it = stream.iterator(); it.hasNext(); ) {
                    insertStmtBuilder.append(quoteValue(it.next())).append(", ");
                }
                insertStmtBuilder.setLength(insertStmtBuilder.length() - 2);
                insertStmtBuilder.append("], ");
            } else if (value instanceof Map) {
                insertStmtBuilder.append("{");
                Map<String, Object> map = (Map<String, Object>) value;
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    insertStmtBuilder.append(quoteValue(entry.getKey())).append(" : ")
                            .append(quoteValue(entry.getValue())).append(", ");
                }
                insertStmtBuilder.setLength(insertStmtBuilder.length() - 2);
                insertStmtBuilder.append("}, ");
            } else {
                insertStmtBuilder.append(value).append(", ");
            }
            values.put(columnIterator.next().split(" ")[0], value);

        }
        return values;
    }

    private String quoteValue(Object value) {
        if (value instanceof String) {
            return '\'' + value.toString() + '\'';
        }
        return value.toString();
    }

    void writeArrayValues(StringBuilder sb, Iterator<?> values) {
        sb.append('[');
        while (values.hasNext()) {
            Object value = values.next();
            if (value instanceof List<?>) {
                writeArrayValues(sb, ((List<?>) value).iterator());
            } else if (value instanceof String) {
                sb.append('\'').append(value).append('\'');
            } else {
                sb.append(value);
            }
            sb.append(", ");
        }
        sb.setLength(sb.length() - 2);
        sb.append(']');
    }
}
