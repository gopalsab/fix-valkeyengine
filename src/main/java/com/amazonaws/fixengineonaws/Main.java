package com.amazonaws.fixengineonaws;

import com.sun.net.httpserver.HttpServer;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;

import java.net.InetSocketAddress;

public class Main {
    public static void main(String[] args) throws Exception {
        // Create and start the HTTP server for health checks
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
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

        // Initialize ValkeyStoreSampler
        ValkeyStoreSampler sampler = new ValkeyStoreSampler();
        Arguments jmeterArgs = new Arguments();
        // Add any necessary arguments here
        jmeterArgs.addArgument("THREADS", "10");
        JavaSamplerContext context = new JavaSamplerContext(jmeterArgs);
        sampler.setupTest(context);

        // Keep the application running
        Thread.currentThread().join();
    }
}
