package com.mfs.idqg.api.bench;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The claim: "RabbitMQ change feed retired subscriber polling". Runs the same sequence of
 * restatements against a real running idqg-api process twice: once measuring the real RabbitMQ
 * consumer's delivery latency (push), once measuring how long a naive fixed-interval poller
 * would take to notice the same change by re-reading the raw endpoint (poll). Both numbers come
 * from this process actually running, not from an interval/2 estimate.
 * Usage: ChangeFeedLatencyBenchmark &lt;apiJar&gt; &lt;docsDir&gt;
 */
public final class ChangeFeedLatencyBenchmark {

    private static final int TRIALS = 30;
    private static final long POLL_INTERVAL_MS = 200;

    public static void main(String[] args) throws Exception {
        Path apiJar = Path.of(args[0]);
        Path docsDir = Path.of(args[1]);
        Files.createDirectories(docsDir);

        try (ServiceProcess service = ServiceProcess.start(apiJar, null)) {
            HttpClient client = HttpClient.newHttpClient();
            ObjectMapper mapper = new ObjectMapper();

            List<Long> pushLatenciesMs = new ArrayList<>();
            List<Long> pollLatenciesMs = new ArrayList<>();

            for (int trial = 0; trial < TRIALS; trial++) {
                String instrumentId = "INS-" + String.format("%04d", trial + 1);
                String date = "2027-01-01";

                // establish an initial value so the poller has something to compare against
                restate(client, service.baseUrl(), instrumentId, date, 100.0);
                double before = readRawPrice(client, mapper, service.baseUrl(), instrumentId, date);

                // --- poll baseline: start a poller on a fixed interval, then change the value ---
                double[] detected = new double[]{Double.NaN};
                long[] pollDetectedAt = new long[]{-1};
                Thread poller = new Thread(() -> {
                    while (pollDetectedAt[0] < 0) {
                        try {
                            double v = readRawPrice(client, mapper, service.baseUrl(), instrumentId, date);
                            if (v != before) {
                                pollDetectedAt[0] = System.currentTimeMillis();
                                detected[0] = v;
                                return;
                            }
                            Thread.sleep(POLL_INTERVAL_MS);
                        } catch (Exception ignored) {
                        }
                    }
                });
                poller.start();
                Thread.sleep(37); // a change lands at a time uncorrelated with the poll schedule
                long changedAt = System.currentTimeMillis();
                restate(client, service.baseUrl(), instrumentId, date, 200.0 + trial);
                poller.join(5000);
                if (pollDetectedAt[0] > 0) {
                    pollLatenciesMs.add(pollDetectedAt[0] - changedAt);
                }

                // --- push: the real RabbitMQ consumer already recorded its own delivery latency ---
                Thread.sleep(300); // give the async consumer time to process
                List<Long> deliveries = benchLatencies(client, mapper, service.baseUrl());
                if (!deliveries.isEmpty()) {
                    pushLatenciesMs.add(deliveries.get(deliveries.size() - 1));
                }
            }

            double pushAvg = avg(pushLatenciesMs);
            double pollAvg = avg(pollLatenciesMs);

            String report = String.format(
                    "RabbitMQ change feed vs fixed-interval polling: detection latency%n"
                    + "Machine: Windows 11 Home (idqg-api process), RabbitMQ 3.9.27 in WSL2 Ubuntu 22.04,%n"
                    + "reached over the WSL2 localhost-forwarding loopback.%n"
                    + "Trials: %d, poll interval: %dms%n"
                    + "Push (RabbitMQ consumer, measured end to end): avg %.1fms over %d samples%n"
                    + "Poll (fixed-interval re-read, measured end to end): avg %.1fms over %d samples%n"
                    + "Claim: RabbitMQ change feed retired subscriber polling%n",
                    TRIALS, POLL_INTERVAL_MS, pushAvg, pushLatenciesMs.size(), pollAvg, pollLatenciesMs.size());

            Files.writeString(docsDir.resolve("changefeed_benchmark_output.txt"), report, StandardCharsets.UTF_8);
            System.out.println(report);
        }
    }

    private static double avg(List<Long> values) {
        return values.isEmpty() ? Double.NaN : values.stream().mapToLong(Long::longValue).average().orElse(Double.NaN);
    }

    private static void restate(HttpClient client, String baseUrl, String instrumentId, String date, double price)
            throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                URI.create(baseUrl + "/api/restatements/pricing/" + instrumentId + "/" + date))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.ofString("{\"price\":" + price + "}"))
                .build();
        client.send(req, HttpResponse.BodyHandlers.discarding());
    }

    private static double readRawPrice(HttpClient client, ObjectMapper mapper, String baseUrl,
                                        String instrumentId, String date) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                URI.create(baseUrl + "/api/pricing/" + instrumentId + "/" + date + "/raw"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return Double.NaN;
        }
        var node = mapper.readTree(resp.body());
        return node.get("price").asDouble();
    }

    private static List<Long> benchLatencies(HttpClient client, ObjectMapper mapper, String baseUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/_bench/changefeed/latencies"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        List<Long> out = new ArrayList<>();
        for (var node : mapper.readTree(resp.body())) {
            out.add(node.asLong());
        }
        return out;
    }
}
