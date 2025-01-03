package com.amazonaws.fixengineonaws;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;

public class LatencyReportGenerator implements JavaSamplerClient {
    private final ConcurrentLinkedQueue<Long> readLatencies = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> writeLatencies = new ConcurrentLinkedQueue<>();
    private String detailedReportFilename;
    private String summaryReportFilename;

    @Override
    public void setupTest(JavaSamplerContext context) {
        // detailedReportFilename = context.getParameter("detailedReportFilename", "detailed_latency_report.csv");
        // summaryReportFilename = context.getParameter("summaryReportFilename", "summary_latency_report.csv");
    }

    @Override
    public SampleResult runTest(JavaSamplerContext context) {
        SampleResult result = new SampleResult();
        result.sampleStart();
        try {
            generateReports(detailedReportFilename, summaryReportFilename);
            result.setSuccessful(true);
            result.setResponseMessage("Latency reports generated successfully");
        } catch (IOException e) {
            result.setSuccessful(false);
            result.setResponseMessage("Error generating latency reports: " + e.getMessage());
        } finally {
            result.sampleEnd();
        }
        return result;
    }

    @Override
    public void teardownTest(JavaSamplerContext context) {
        // Clean up resources if needed
    }

    @Override
    public Arguments getDefaultParameters() {
        Arguments params = new Arguments();
        params.addArgument("detailedReportFilename", "detailed_latency_report.csv");
        params.addArgument("summaryReportFilename", "summary_latency_report.csv");
        return params;
    }

    public void addReadLatency(long latency) {
        readLatencies.add(latency);
    }

    public void addWriteLatency(long latency) {
        writeLatencies.add(latency);
    }

    public void generateReports(String detailedReportFilename, String summaryReportFilename) throws IOException {
        generateDetailedReport(detailedReportFilename);
        generateSummaryReport(summaryReportFilename);
    }

    private void generateDetailedReport(String filename) throws IOException {
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write("Operation,Latency(ms)\n");
            for (Long latency : readLatencies) {
                writer.write(String.format("Read,%d\n", latency));
            }
            for (Long latency : writeLatencies) {
                writer.write(String.format("Write,%d\n", latency));
            }
        }
    }

    private void generateSummaryReport(String filename) throws IOException {
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write("Metric,Read,Write\n");
            writer.write(String.format("Average Latency,%.2f,%.2f\n", calculateAverage(readLatencies), calculateAverage(writeLatencies)));
            writer.write(String.format("50th Percentile,%.2f,%.2f\n", calculatePercentile(readLatencies, 50), calculatePercentile(writeLatencies, 50)));
            writer.write(String.format("90th Percentile,%.2f,%.2f\n", calculatePercentile(readLatencies, 90), calculatePercentile(writeLatencies, 90)));
            writer.write(String.format("95th Percentile,%.2f,%.2f\n", calculatePercentile(readLatencies, 95), calculatePercentile(writeLatencies, 95)));
            writer.write(String.format("99th Percentile,%.2f,%.2f\n", calculatePercentile(readLatencies, 99), calculatePercentile(writeLatencies, 99)));
        }
    }

    private double calculateAverage(ConcurrentLinkedQueue<Long> latencies) {
        if (latencies.isEmpty()) return 0;
        return latencies.stream().mapToDouble(Long::doubleValue).average().orElse(0);
    }

    private double calculatePercentile(ConcurrentLinkedQueue<Long> latencies, int percentile) {
        if (latencies.isEmpty()) return 0;
        List<Long> sortedLatencies = new ArrayList<>(latencies);
        Collections.sort(sortedLatencies);
        int index = (int) Math.ceil(percentile / 100.0 * sortedLatencies.size()) - 1;
        return sortedLatencies.get(index);
    }
}
