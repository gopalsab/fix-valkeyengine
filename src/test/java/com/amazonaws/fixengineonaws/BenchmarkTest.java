package com.amazonaws.fixengineonaws;

import org.junit.Test;
import quickfix.SessionSettings;
import quickfix.SessionID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class BenchmarkTest {
    private static final int NUM_CLIENTS = 1;
    private static final int TEST_DURATION_SECONDS = 60;
    private static final List<ValkeyStore> stores = new ArrayList<>();

    // Amazon MemoryDB connection details
    private static final String MEMORYDB_ENDPOINT = "clustercfg.fix-performance-cluster-us-east-1.wxfcuv.memorydb.us-east-1.amazonaws.com";
    private static final int MEMORYDB_PORT = 6379;
    private static final boolean USE_SSL = true;

    @Test
    public void runBenchmark() throws Exception {
        SessionSettings settings = new SessionSettings();
        System.out.println("Connecting to MemoryDB at: " + MEMORYDB_ENDPOINT + ":" + MEMORYDB_PORT);
        System.out.println("Using SSL: " + USE_SSL);
        
        for (int i = 0; i < NUM_CLIENTS; i++) {
            SessionID sessionID = new SessionID("FIX.4.4", "SENDER" + i, "TARGET" + i);
            
            // Set session-specific settings
            settings.setString(sessionID, ValkeyStore.Fields.CONFIG_HOST, MEMORYDB_ENDPOINT);
            settings.setLong(sessionID, ValkeyStore.Fields.CONFIG_PORT, MEMORYDB_PORT);
            settings.setBool(sessionID, ValkeyStore.Fields.CONFIG_SSL, USE_SSL);
            settings.setString(sessionID, ValkeyStore.Fields.CONFIG_SESSIONS_TABLE, "sessions_" + i);
            settings.setString(sessionID, ValkeyStore.Fields.CONFIG_MESSAGES_TABLE, "messages_" + i);

            stores.add(new ValkeyStore(settings, sessionID));
        }

        ExecutorService executor = Executors.newFixedThreadPool(NUM_CLIENTS);

        System.out.println("Workload type,Throughput (requests per second),Latency p50 (milliseconds),Latency p90 (milliseconds)");

        // Read-only workload
        runWorkload(executor, 1.0, 0.0);
        printResults("Read only");

        // Write-only workload
        runWorkload(executor, 0.0, 1.0);
        printResults("Write only");

        // Mixed workload (80% read, 20% write)
        runWorkload(executor, 0.8, 0.2);
        printResults("Mixed (80% read, 20% write)");

        executor.shutdown();
        for (ValkeyStore store : stores) {
            store.close();
        }
    }

    private void runWorkload(ExecutorService executor, double readRatio, double writeRatio) throws InterruptedException {
        for (ValkeyStore store : stores) {
            store.resetMetrics();
        }

        for (ValkeyStore store : stores) {
            executor.submit(() -> {
                long endTime = System.currentTimeMillis() + TEST_DURATION_SECONDS * 1000;
                while (System.currentTimeMillis() < endTime) {
                    try {
                        if (Math.random() < readRatio) {
                            store.get(1, 10, new ArrayList<>());
                        } else if (Math.random() < writeRatio) {
                            store.set(1, "TEST_MESSAGE");
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
        executor.awaitTermination(TEST_DURATION_SECONDS, TimeUnit.SECONDS);
    }

    private void printResults(String workloadType) {
        long totalOperations = 0;
        double totalReadLatency50 = 0;
        double totalReadLatency90 = 0;
        double totalWriteLatency50 = 0;
        double totalWriteLatency90 = 0;

        for (ValkeyStore store : stores) {
            totalOperations += store.getReadCount() + store.getWriteCount();
            totalReadLatency50 += store.getReadLatencyPercentile(50);
            totalReadLatency90 += store.getReadLatencyPercentile(90);
            totalWriteLatency50 += store.getWriteLatencyPercentile(50);
            totalWriteLatency90 += store.getWriteLatencyPercentile(90);
        }

        long throughput = totalOperations / TEST_DURATION_SECONDS;
        double avgLatency50 = (totalReadLatency50 + totalWriteLatency50) / (2 * stores.size());
        double avgLatency90 = (totalReadLatency90 + totalWriteLatency90) / (2 * stores.size());

        System.out.printf("%s,%d,%.1f,%.1f%n", workloadType, throughput, avgLatency50, avgLatency90);
    }
}
