package com.amazonaws.fixengineonaws;

import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import quickfix.SessionSettings;
import quickfix.SessionID;

import quickfix.field.*;
import quickfix.fix44.NewOrderSingle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;

public class ValkeyStoreSampler extends AbstractJavaSamplerClient {
    private static final String MEMORYDB_ENDPOINT = "clustercfg.fix-performance-cluster-us-east-1.wxfcuv.memorydb.us-east-1.amazonaws.com";
    private static final int MEMORYDB_PORT = 6379;
    private static final boolean USE_SSL = true;

    private ValkeyStore valkeyStore;
    private static final LatencyReportGenerator reportGenerator = new LatencyReportGenerator();
    private int messageSequence = 1;
    private HttpServer server;

    @Override
    public void setupTest(JavaSamplerContext context) {
        try {
            System.out.println("Connecting to MemoryDB at: " + MEMORYDB_ENDPOINT + ":" + MEMORYDB_PORT);
            SessionSettings settings = new SessionSettings();
            SessionID sessionID = new SessionID("FIX.4.4", "SENDER", "TARGET");

            settings.setString(sessionID, ValkeyStore.Fields.CONFIG_HOST, MEMORYDB_ENDPOINT);
            settings.setLong(sessionID, ValkeyStore.Fields.CONFIG_PORT, MEMORYDB_PORT);
            settings.setBool(sessionID, ValkeyStore.Fields.CONFIG_SSL, USE_SSL);
            settings.setString(sessionID, ValkeyStore.Fields.CONFIG_SESSIONS_TABLE, "sessions_jmeter");
            settings.setString(sessionID, ValkeyStore.Fields.CONFIG_MESSAGES_TABLE, "messages_jmeter");

            System.out.println("Initializing ValkeyStore...");
            valkeyStore = new ValkeyStore(settings, sessionID);
            System.out.println("ValkeyStore initialized successfully");

            // Create and start the HTTP server for health checks
            startHealthCheckServer();
        } catch (Exception e) {
            System.err.println("Error initializing ValkeyStore: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void startHealthCheckServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/health", (exchange -> {
            String response = "OK";
            exchange.sendResponseHeaders(200, response.length());
            try (var os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }));
        server.setExecutor(null);
        server.start();
        System.out.println("Health check server started on port 8080");
    }

    @Override
    public SampleResult runTest(JavaSamplerContext context) {
        SampleResult result = new SampleResult();
        result.sampleStart();

        try {
            if (valkeyStore == null) {
                throw new IllegalStateException("ValkeyStore is not initialized");
            }

            String workloadType = context.getParameter("WORKLOAD_TYPE", "MIXED");
            double readRatio = Double.parseDouble(context.getParameter("READ_RATIO", "0.8"));

            if (workloadType.equals("READ_ONLY") || (workloadType.equals("MIXED") && Math.random() < readRatio)) {
                performRead(result);
            } else {
                performWrite(result);
            }

            result.setSuccessful(true);
            result.setResponseCode("200");
        } catch (Exception e) {
            result.setSuccessful(false);
            result.setResponseCode("500");
            result.setResponseMessage("Error: " + e.getMessage());
        } finally {
            result.sampleEnd();
        }

        return result;
    }

    private void performRead(SampleResult result) throws Exception {
        long startTime = System.nanoTime();
        ArrayList<String> messages = new ArrayList<>();
        valkeyStore.get(1, 10, messages);
        long latency = System.nanoTime() - startTime;
        reportGenerator.addReadLatency(latency / 1_000_000); // Convert to milliseconds
        result.setResponseData("Read " + messages.size() + " messages", "UTF-8");
    }

    private void performWrite(SampleResult result) throws Exception {
        NewOrderSingle order = createNewOrderSingle();
        String fixMessage = order.toString();

        long startTime = System.nanoTime();
        valkeyStore.set(messageSequence++, fixMessage);
        long latency = System.nanoTime() - startTime;
        reportGenerator.addWriteLatency(latency / 1_000_000); // Convert to milliseconds
        result.setResponseData("Wrote FIX message: " + fixMessage, "UTF-8");
    }

    private NewOrderSingle createNewOrderSingle() {
        NewOrderSingle order = new NewOrderSingle(
                new ClOrdID(UUID.randomUUID().toString()),
                new Side(Side.BUY),
                new TransactTime(),
                new OrdType(OrdType.MARKET)
        );

        order.set(new Symbol("AAPL"));
        order.set(new OrderQty(100));
        order.set(new HandlInst('1'));
        order.set(new TimeInForce(TimeInForce.DAY));

        return order;
    }

    @Override
    public void teardownTest(JavaSamplerContext context) {
        if (valkeyStore != null) {
            try {
                valkeyStore.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (server != null) {
            server.stop(0);
            System.out.println("Health check server stopped");
        }
        try {
            reportGenerator.generateReports(
                "memorydb_detailed_latency_report.csv",
                "memorydb_summary_latency_report.csv"
            );
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
