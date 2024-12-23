package com.amazonaws.fixengineonaws;

import glide.api.GlideClusterClient;
import glide.api.models.configuration.NodeAddress;
import glide.api.models.configuration.GlideClusterClientConfiguration;
import glide.api.models.GlideString;
import quickfix.MessageStore;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SystemTime;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static glide.api.models.GlideString.gs;


public class ValkeyStore implements MessageStore, AutoCloseable {
    public static final class Fields {
        public static final String SESSION_CREATION_TIME = "creation_time";
        public static final String SESSION_TARGET_INCOMING_SEQNUM = "incoming_seqnum";
        public static final String SESSION_SENDER_OUTGOING_SEQNUM = "outgoing_seqnum";
        
        public static final String CONFIG_SESSIONS_TABLE = "ValkeyStoreSessionsTableName";
        public static final String CONFIG_MESSAGES_TABLE = "ValkeyStoreMessagesTableName";
        public static final String CONFIG_HOST = "ValkeyHost";
        public static final String CONFIG_PORT = "ValkeyPort";
        public static final String CONFIG_SSL = "ValkeyUseSsl";
    }

    private final SessionID sessionID;
    private final String sessionTableName;
    private final String messageTableName;
    private final GlideClusterClient client;
    private final PerformanceMetrics metrics;

    public ValkeyStore(SessionSettings settings, SessionID sessionID) throws Exception {
        this.sessionID = sessionID;
        this.metrics = new PerformanceMetrics();
        
        String sessionUniqueId = createSessionUniqueId(sessionID);
        this.sessionTableName = settings.getString(sessionID, Fields.CONFIG_SESSIONS_TABLE) + sessionUniqueId;
        this.messageTableName = settings.getString(sessionID, Fields.CONFIG_MESSAGES_TABLE) + sessionUniqueId;
        
        // this.client = createGlideClient(settings, sessionID);
        this.client = createGlideClusterClient(settings, sessionID);
        initializeSession();
    }

    private String createSessionUniqueId(SessionID sessionID) {
        return sessionID.toString().replace(':', '_');
    }

    // private GlideClient createGlideClient(SessionSettings settings, SessionID sessionID) throws Exception {
    //     GlideClientConfiguration config = GlideClientConfiguration.builder()
    //             .address(NodeAddress.builder()
    //                     .host(settings.getString(sessionID, Fields.CONFIG_HOST))
    //                     .port(settings.getInt(sessionID, Fields.CONFIG_PORT))
    //                     .build())
    //             .useTLS(settings.getBool(sessionID, Fields.CONFIG_SSL))
    //             .build();

    //     return GlideClient.createClient(config).get();
    // }
    private GlideClusterClient createGlideClusterClient(SessionSettings settings, SessionID sessionID) throws Exception {
    String host = settings.getString(sessionID, Fields.CONFIG_HOST);
    int port = settings.getInt(sessionID, Fields.CONFIG_PORT);
    boolean useSsl = settings.getBool(sessionID, Fields.CONFIG_SSL);

    System.out.println("Creating GlideClusterClient for " + sessionID + " with host: " + host + ", port: " + port + ", SSL: " + useSsl);

    GlideClusterClientConfiguration config = GlideClusterClientConfiguration.builder()
            .address(NodeAddress.builder()
                    .host(host)
                    .port(port)
                    .build())
            .useTLS(useSsl)
            .build();

    return GlideClusterClient.createClient(config).get();
    }
    private void initializeSession() throws IOException {
        try {
            if (!sessionExists()) {
                createNewSession();
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Failed to initialize session", e);
        }
    }

    private boolean sessionExists() throws InterruptedException, ExecutionException {
        return client.exists(new GlideString[]{gs(sessionTableName)}).get() != 0;
    }

    private void createNewSession() throws InterruptedException, ExecutionException {
        Calendar creationTime = SystemTime.getUtcCalendar();
        Map<GlideString, GlideString> sessionData = new HashMap<>();
        sessionData.put(gs(Fields.SESSION_CREATION_TIME), gs(String.valueOf(creationTime.getTimeInMillis())));
        sessionData.put(gs(Fields.SESSION_TARGET_INCOMING_SEQNUM), gs("1"));
        sessionData.put(gs(Fields.SESSION_SENDER_OUTGOING_SEQNUM), gs("1"));
        client.hset(gs(sessionTableName), sessionData).get();
    }

    @Override
    public void get(int startSequence, int endSequence, Collection<String> messages) throws IOException {
        metrics.measureReadOperation(() -> {
            try {
                for (int i = startSequence; i <= endSequence; i++) {
                    GlideString message = client.hget(gs(messageTableName), gs(String.valueOf(i))).get();
                    if (message != null && !message.toString().isEmpty()) {
                        messages.add(message.toString());
                    }
                }
            } catch (InterruptedException | ExecutionException e) {
                throw new IOException("Failed to get messages", e);
            }
        });
    }

    @Override
    public boolean set(int sequence, String message) throws IOException {
        metrics.measureWriteOperation(() -> {
            try {
                Map<GlideString, GlideString> map = new HashMap<>();
                map.put(gs(String.valueOf(sequence)), gs(message));
                client.hset(gs(messageTableName), map).get();
            } catch (InterruptedException | ExecutionException e) {
                throw new IOException("Failed to set message", e);
            }
        });
        return true;
    }

    private void updateSequenceNumber(String field, int value) throws IOException {
        try {
            Map<GlideString, GlideString> map = new HashMap<>();
            map.put(gs(field), gs(String.valueOf(value)));
            client.hset(gs(sessionTableName), map).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Failed to update sequence number", e);
        }
    }

    @Override
    public void setNextSenderMsgSeqNum(int next) throws IOException {
        updateSequenceNumber(Fields.SESSION_SENDER_OUTGOING_SEQNUM, next);
    }

    @Override
    public void setNextTargetMsgSeqNum(int next) throws IOException {
        updateSequenceNumber(Fields.SESSION_TARGET_INCOMING_SEQNUM, next);
    }

    @Override
    public int getNextSenderMsgSeqNum() throws IOException {
        try {
            GlideString val = client.hget(gs(sessionTableName), gs(Fields.SESSION_SENDER_OUTGOING_SEQNUM)).get();
            return Integer.parseInt(val.toString());
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Failed to get next sender message sequence number", e);
        }
    }

    @Override
    public int getNextTargetMsgSeqNum() throws IOException {
        try {
            GlideString val = client.hget(gs(sessionTableName), gs(Fields.SESSION_TARGET_INCOMING_SEQNUM)).get();
            return Integer.parseInt(val.toString());
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Failed to get next target message sequence number", e);
        }
    }

    @Override
    public Date getCreationTime() throws IOException {
        try {
            GlideString val = client.hget(gs(sessionTableName), gs(Fields.SESSION_CREATION_TIME)).get();
            return new Date(Long.parseLong(val.toString()));
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Failed to get creation time", e);
        }
    }

    @Override
    public void reset() throws IOException {
        try {
            client.del(new GlideString[]{gs(messageTableName), gs(sessionTableName)}).get();
            initializeSession();
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Failed to reset", e);
        }
    }

    @Override
    public void refresh() throws IOException {
        // No implementation needed for Valkey
    }

    @Override
    public void incrNextSenderMsgSeqNum() throws IOException {
        setNextSenderMsgSeqNum(getNextSenderMsgSeqNum() + 1);
    }

    @Override
    public void incrNextTargetMsgSeqNum() throws IOException {
        setNextTargetMsgSeqNum(getNextTargetMsgSeqNum() + 1);
    }

    @Override
    public void close() throws IOException {
        try {
            client.close();
        } catch (Exception e) {
            throw new IOException("Failed to close the client", e);
        }
    }


    public void resetMetrics() {
        metrics.reset();
    }

    public long getReadCount() {
        return metrics.getReadCount();
    }

    public long getWriteCount() {
        return metrics.getWriteCount();
    }

    public double getReadLatencyPercentile(int percentile) {
        return metrics.getReadLatencyPercentile(percentile);
    }

    public double getWriteLatencyPercentile(int percentile) {
        return metrics.getWriteLatencyPercentile(percentile);
    }

    public PerformanceMetrics getMetrics() {
        return metrics;
    }
}

class PerformanceMetrics {
    private final AtomicLong totalReadTime = new AtomicLong(0);
    private final AtomicLong totalWriteTime = new AtomicLong(0);
    private final AtomicLong readCount = new AtomicLong(0);
    private final AtomicLong writeCount = new AtomicLong(0);
    private final List<Long> readLatencies = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> writeLatencies = Collections.synchronizedList(new ArrayList<>());

    public void measureReadOperation(IOOperation operation) throws IOException {
        long startTime = System.nanoTime();
        operation.execute();
        long latency = System.nanoTime() - startTime;
        totalReadTime.addAndGet(latency);
        readCount.incrementAndGet();
        readLatencies.add(latency / 1_000_000); // Convert to milliseconds
    }

    public void measureWriteOperation(IOOperation operation) throws IOException {
        long startTime = System.nanoTime();
        operation.execute();
        long latency = System.nanoTime() - startTime;
        totalWriteTime.addAndGet(latency);
        writeCount.incrementAndGet();
        writeLatencies.add(latency / 1_000_000); // Convert to milliseconds
    }

    public void reset() {
        totalReadTime.set(0);
        totalWriteTime.set(0);
        readCount.set(0);
        writeCount.set(0);
        readLatencies.clear();
        writeLatencies.clear();
    }

    public double getAverageReadLatency() {
        long count = readCount.get();
        return count > 0 ? (double) totalReadTime.get() / count / 1_000_000 : 0; // Convert to milliseconds
    }

    public double getAverageWriteLatency() {
        long count = writeCount.get();
        return count > 0 ? (double) totalWriteTime.get() / count / 1_000_000 : 0; // Convert to milliseconds
    }

    public long getReadCount() {
        return readCount.get();
    }

    public long getWriteCount() {
        return writeCount.get();
    }

    public double getReadLatencyPercentile(int percentile) {
        return getPercentile(readLatencies, percentile);
    }

    public double getWriteLatencyPercentile(int percentile) {
        return getPercentile(writeLatencies, percentile);
    }

    private double getPercentile(List<Long> latencies, int percentile) {
        if (latencies.isEmpty()) {
            return 0;
        }
        List<Long> sortedLatencies = new ArrayList<>(latencies);
        Collections.sort(sortedLatencies);
        int index = (int) Math.ceil(percentile / 100.0 * sortedLatencies.size()) - 1;
        return sortedLatencies.get(index);
    }
}

@FunctionalInterface
interface IOOperation {
    void execute() throws IOException;
}
