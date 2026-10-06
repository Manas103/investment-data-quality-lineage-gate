package com.mfs.idqg.api.changefeed;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one queue the change feed publishes to and the cache-invalidation consumer reads from. */
@Configuration
public class ChangeFeedConfig {

    public static final String QUEUE_NAME = "idqg.reference-data.changes";

    @Bean
    public Queue changeFeedQueue() {
        return new Queue(QUEUE_NAME, true);
    }
}
