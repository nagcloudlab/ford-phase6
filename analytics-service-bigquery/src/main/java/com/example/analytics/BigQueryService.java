package com.example.analytics;

import com.google.cloud.bigquery.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class BigQueryService {

    private static final Logger log = LoggerFactory.getLogger(BigQueryService.class);

    private final BigQuery bigQuery;
    private final String datasetId;
    private final String tableId;
    private final String projectId;

    private final AtomicLong rowsInserted = new AtomicLong(0);
    private final AtomicLong insertErrors = new AtomicLong(0);

    public BigQueryService(BigQuery bigQuery,
                           @Value("${bigquery.dataset:upi_analytics}") String datasetId,
                           @Value("${bigquery.table:transactions}") String tableId,
                           @Value("${spring.cloud.gcp.project-id:test-project}") String projectId) {
        this.bigQuery = bigQuery;
        this.datasetId = datasetId;
        this.tableId = tableId;
        this.projectId = projectId;
    }

    @PostConstruct
    public void init() {
        log.info("BigQueryService initialized | project={} | dataset={} | table={}",
                projectId, datasetId, tableId);
        ensureDatasetAndTable();
    }

    /**
     * Create dataset and table if they don't exist.
     */
    private void ensureDatasetAndTable() {
        try {
            // Create dataset if not exists
            Dataset dataset = bigQuery.getDataset(datasetId);
            if (dataset == null) {
                DatasetInfo datasetInfo = DatasetInfo.newBuilder(datasetId)
                        .setLocation("US")
                        .build();
                bigQuery.create(datasetInfo);
                log.info("Created BigQuery dataset: {}", datasetId);
            } else {
                log.info("BigQuery dataset already exists: {}", datasetId);
            }

            // Create table if not exists
            TableId table = TableId.of(datasetId, tableId);
            Table existingTable = bigQuery.getTable(table);
            if (existingTable == null) {
                Schema schema = Schema.of(
                        Field.of("transaction_id", StandardSQLTypeName.INT64),
                        Field.of("sender_upi_id", StandardSQLTypeName.STRING),
                        Field.of("receiver_upi_id", StandardSQLTypeName.STRING),
                        Field.of("amount", StandardSQLTypeName.NUMERIC),
                        Field.of("status", StandardSQLTypeName.STRING),
                        Field.of("timestamp", StandardSQLTypeName.TIMESTAMP),
                        Field.of("ingested_at", StandardSQLTypeName.TIMESTAMP)
                );
                TableDefinition tableDefinition = StandardTableDefinition.of(schema);
                TableInfo tableInfo = TableInfo.newBuilder(table, tableDefinition).build();
                bigQuery.create(tableInfo);
                log.info("Created BigQuery table: {}.{}", datasetId, tableId);
            } else {
                log.info("BigQuery table already exists: {}.{}", datasetId, tableId);
            }
        } catch (Exception e) {
            log.error("Failed to initialize BigQuery dataset/table: {}", e.getMessage(), e);
        }
    }

    /**
     * Stream a transaction event into BigQuery using insertAll (streaming insert).
     */
    public void streamTransaction(TransactionEvent event) {
        try {
            TableId table = TableId.of(datasetId, tableId);

            Map<String, Object> row = new HashMap<>();
            row.put("transaction_id", event.getTransactionId());
            row.put("sender_upi_id", event.getSenderUpiId());
            row.put("receiver_upi_id", event.getReceiverUpiId());
            row.put("amount", event.getAmount().doubleValue());
            row.put("status", event.getStatus());
            if (event.getTimestamp() != null) {
                row.put("timestamp", event.getTimestamp().toString());
            }
            row.put("ingested_at", java.time.Instant.now().toString());

            InsertAllRequest request = InsertAllRequest.newBuilder(table)
                    .addRow(row)
                    .build();

            InsertAllResponse response = bigQuery.insertAll(request);

            if (response.hasErrors()) {
                response.getInsertErrors().forEach((index, errors) ->
                        errors.forEach(error ->
                                log.error("BQ INSERT ERROR | row={} | error={}", index, error.getMessage())
                        )
                );
                insertErrors.incrementAndGet();
            } else {
                long count = rowsInserted.incrementAndGet();
                log.info("BQ STREAMED | txnId={} | from={} | to={} | amount={} | totalRows={}",
                        event.getTransactionId(), event.getSenderUpiId(),
                        event.getReceiverUpiId(), event.getAmount(), count);
            }
        } catch (Exception e) {
            insertErrors.incrementAndGet();
            log.error("BQ STREAM FAILED | txnId={} | error={}",
                    event.getTransactionId(), e.getMessage());
        }
    }

    public long getRowsInserted() { return rowsInserted.get(); }
    public long getInsertErrors() { return insertErrors.get(); }
    public String getDatasetId() { return datasetId; }
    public String getTableId() { return tableId; }
}
