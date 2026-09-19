package com.evas.alerting;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around {@link RabbitTemplate} so callers never need to know
 * the exchange name. Always called from inside the same DB transaction that
 * writes the corresponding audit row (see {@code AlertingService}) — if the
 * publish throws (broker unreachable), the transaction rolls back and
 * nothing is persisted either, so the next position tick simply retries.
 */
@Component
@RequiredArgsConstructor
public class CommandPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publish(String routingKey, Object message) {
        rabbitTemplate.convertAndSend(RabbitTopologyConfig.DEVICE_COMMANDS_EXCHANGE, routingKey, message);
    }
}
