package com.mfs.idqg.api;

import com.mfs.idqg.api.store.BitemporalStore;
import com.mfs.idqg.api.store.CorpusLoader;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Loads a generated corpus into the bitemporal store at startup if IDQG_CORPUS_DIR is set, so a
 * freshly started process has real data to serve reads over without a separate seeding step.
 */
@SpringBootApplication
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }

    @Bean
    public CommandLineRunner seedCorpus(BitemporalStore store) {
        return args -> {
            String corpusDir = System.getenv("IDQG_CORPUS_DIR");
            if (corpusDir == null || corpusDir.isBlank()) {
                return;
            }
            Instant ingestedAt = Instant.now();
            Path dir = Path.of(corpusDir);
            int sm = CorpusLoader.loadSecurityMaster(dir.resolve("security_master.csv"), store, ingestedAt);
            int pricing = CorpusLoader.loadPricing(dir.resolve("pricing.csv"), store, ingestedAt);
            int positions = CorpusLoader.loadPositions(dir.resolve("positions.csv"), store, ingestedAt);
            System.out.println("Seeded bitemporal store: " + sm + " security-master rows, "
                    + pricing + " pricing rows, " + positions + " position rows, from " + corpusDir);
        };
    }
}
