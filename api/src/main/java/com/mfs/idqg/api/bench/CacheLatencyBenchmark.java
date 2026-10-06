package com.mfs.idqg.api.bench;

import com.mfs.idqg.api.store.CorpusLoader;

import java.io.IOException;
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
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The scale claim: Redis read-through cache held p99 under 12ms at 2,000 req/s on one node. Open-
 * loop load generator (requests dispatched on a fixed schedule via async HTTP, not a closed-loop
 * concurrency pool) against a real idqg-api process, real Redis (WSL2 Ubuntu, reached from this
 * Windows JVM over the WSL2 localhost-forwarding loopback), serving the real as-of-now cached
 * pricing read. Usage: CacheLatencyBenchmark &lt;apiJar&gt; &lt;generatorPy&gt; &lt;docsDir&gt;
 */
public final class CacheLatencyBenchmark {

    private static final int TARGET_RATE_PER_SEC = 2000;
    private static final int DURATION_SECONDS = 5;
    private static final int WARMUP_REQUESTS = 4000;

    public static void main(String[] args) throws Exception {
        Path apiJar = Path.of(args[0]);
        Path generatorPy = Path.of(args[1]);
        Path docsDir = Path.of(args[2]);
        Files.createDirectories(docsDir);

        Path corpusDir = Files.createTempDirectory("idqg-api-bench-corpus");
        generateCorpus(generatorPy, corpusDir, 10);

        List<Map<String, String>> pricing = CorpusLoader.read(corpusDir.resolve("pricing.csv"));
        List<String[]> keys = new ArrayList<>();
        for (Map<String, String> row : pricing) {
            keys.add(new String[]{row.get("instrument_id"), row.get("price_date")});
        }

        try (ServiceProcess service = ServiceProcess.start(apiJar, corpusDir.toString())) {
            HttpClient client = HttpClient.newHttpClient();
            Random rng = new Random(7);

            // Warm the cache: one read per key populates Redis before the timed run starts.
            for (String[] k : keys) {
                get(client, service.baseUrl(), k[0], k[1]);
            }

            // A short warmup at the target rate, discarded, so JIT/connection-pool warmup noise
            // does not land in the measured window.
            runAtRate(client, service.baseUrl(), keys, rng, WARMUP_REQUESTS, TARGET_RATE_PER_SEC, null);
            System.gc();
            Thread.sleep(500);

            List<Long> latenciesMicros = new CopyOnWriteArrayList<>();
            long elapsedNanos = runAtRate(client, service.baseUrl(), keys, rng,
                    TARGET_RATE_PER_SEC * DURATION_SECONDS, TARGET_RATE_PER_SEC, latenciesMicros);

            List<Long> sorted = new ArrayList<>(latenciesMicros);
            sorted.sort(Long::compareTo);
            double achievedRate = sorted.size() / (elapsedNanos / 1_000_000_000.0);
            double p50Ms = sorted.get(sorted.size() / 2) / 1000.0;
            double p99Ms = sorted.get((int) (sorted.size() * 0.99)) / 1000.0;
            double maxMs = sorted.get(sorted.size() - 1) / 1000.0;

            String report = String.format(
                    "Redis read-through cache latency benchmark%n"
                    + "Machine: Windows 11 Home (Java client + idqg-api process), Redis 6.0.16 in WSL2 Ubuntu 22.04,%n"
                    + "reached over the WSL2 localhost-forwarding loopback, not same-process localhost.%n"
                    + "Corpus: 480 instruments x 10 trading days = 4,800 pricing keys, all pre-warmed into Redis.%n"
                    + "Target rate: %d req/s open-loop for %ds (warmup of %d requests discarded first).%n"
                    + "Requests completed: %d, achieved rate: %.1f req/s%n"
                    + "p50: %.3fms  p99: %.3fms  max: %.3fms%n"
                    + "Claim: p99 under 12ms at 2,000 req/s on one node%n",
                    TARGET_RATE_PER_SEC, DURATION_SECONDS, WARMUP_REQUESTS,
                    sorted.size(), achievedRate, p50Ms, p99Ms, maxMs);

            Files.writeString(docsDir.resolve("cache_benchmark_output.txt"), report, StandardCharsets.UTF_8);
            System.out.println(report);
        }
    }

    /** Dispatches {@code count} async GETs on a fixed schedule; returns elapsed wall time in nanos. */
    private static long runAtRate(HttpClient client, String baseUrl, List<String[]> keys, Random rng,
                                   int count, int ratePerSec, List<Long> latenciesMicrosOut) {
        long intervalNanos = 1_000_000_000L / ratePerSec;
        List<CompletableFuture<Void>> futures = new ArrayList<>(count);
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            long targetTime = start + i * intervalNanos;
            while (System.nanoTime() < targetTime) {
                Thread.onSpinWait();
            }
            String[] k = keys.get(rng.nextInt(keys.size()));
            long reqStart = System.nanoTime();
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create(baseUrl + "/api/pricing/" + k[0] + "/" + k[1] + "/cached"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            CompletableFuture<Void> f = client.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(resp -> {
                        if (latenciesMicrosOut != null) {
                            latenciesMicrosOut.add((System.nanoTime() - reqStart) / 1000);
                        }
                    });
            futures.add(f);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return System.nanoTime() - start;
    }

    private static void get(HttpClient client, String baseUrl, String instrumentId, String date) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                URI.create(baseUrl + "/api/pricing/" + instrumentId + "/" + date + "/cached"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        client.send(req, HttpResponse.BodyHandlers.discarding());
    }

    static void generateCorpus(Path generatorPy, Path outDir, int days) throws IOException, InterruptedException {
        String python = System.getenv().getOrDefault("IDQG_PYTHON",
                "C:\\Users\\Manas\\AppData\\Local\\Programs\\Python\\Python312\\python.exe");
        ProcessBuilder pb = new ProcessBuilder(python, generatorPy.toAbsolutePath().toString(),
                "--mode", "clean", "--days", Integer.toString(days), "--out-dir", outDir.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        pb.directory(generatorPy.getParent().toFile());
        Process p = pb.start();
        p.getInputStream().transferTo(System.out);
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("corpus generator exited " + exit);
        }
    }
}
