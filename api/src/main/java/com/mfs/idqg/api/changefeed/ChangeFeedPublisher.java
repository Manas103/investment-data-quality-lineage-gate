package com.mfs.idqg.api.changefeed;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes one message per restatement to the RabbitMQ change feed. This is the mechanism that
 * retires subscriber polling: a subscriber used to have to ask "has key K changed?" on a timer;
 * now it is pushed a message the instant a restatement happens. {@code ChangeFeedLatencyBenchmark}
 * measures the before/after detection-latency difference directly.
 */
@Component
public class ChangeFeedPublisher {

    private final RabbitTemplate rabbitTemplate;

    public ChangeFeedPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /** {@code changedAtEpochMillis} travels in the message body so a consumer can measure delivery latency. */
    public void publishChange(String key, long changedAtEpochMillis) {
        rabbitTemplate.convertAndSend(ChangeFeedConfig.QUEUE_NAME, key + "|" + changedAtEpochMillis);
    }
}
