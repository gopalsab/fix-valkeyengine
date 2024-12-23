package com.amazonaws.fixengineonaws;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import quickfix.SessionSettings;
import quickfix.SessionID;
import java.util.Map;
import java.util.ArrayList;

public class LambdaHandler implements RequestHandler<Map<String, String>, String> {
    @Override
    public String handleRequest(Map<String, String> input, Context context) {
        try (ValkeyStore store = createValkeyStore()) {
            // Perform some operations
            store.set(1, "TEST_MESSAGE");
            ArrayList<String> messages = new ArrayList<>();
            store.get(1, 1, messages);
            String message = messages.isEmpty() ? "No message retrieved" : messages.get(0);

            PerformanceMetrics metrics = store.getMetrics();
            return String.format("Message: %s, Avg Read Latency: %.2f ms, Avg Write Latency: %.2f ms",
                    message, metrics.getAverageReadLatency(), metrics.getAverageWriteLatency());
        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private ValkeyStore createValkeyStore() throws Exception {
        SessionSettings settings = new SessionSettings();
        settings.setString(ValkeyStore.Fields.CONFIG_HOST, System.getenv("VALKEY_HOST"));
        settings.setString(ValkeyStore.Fields.CONFIG_PORT, System.getenv("VALKEY_PORT"));
        settings.setString(ValkeyStore.Fields.CONFIG_SSL, System.getenv("VALKEY_USE_SSL"));
        settings.setString(ValkeyStore.Fields.CONFIG_SESSIONS_TABLE, "sessions_");
        settings.setString(ValkeyStore.Fields.CONFIG_MESSAGES_TABLE, "messages_");

        SessionID sessionID = new SessionID("FIX.4.4", "SENDER", "TARGET");
        return new ValkeyStore(settings, sessionID);
    }
}
