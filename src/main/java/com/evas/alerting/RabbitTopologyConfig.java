package com.evas.alerting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One topic exchange for every device command. The backend only ever
 * publishes here — it never declares or binds a queue. Each device
 * (real or, for now, a fake consumer in the external simulator) owns its
 * own queue and binds it using its {@code routing_key} column
 * ({@code Lamppost}/{@code TrafficSignal}), so adding a new device never
 * requires a backend deploy.
 */
@Configuration
public class RabbitTopologyConfig {

    public static final String DEVICE_COMMANDS_EXCHANGE = "evas.device.commands";

    @Bean
    public TopicExchange deviceCommandsExchange() {
        return new TopicExchange(DEVICE_COMMANDS_EXCHANGE, true, false);
    }

    /**
     * Spring Boot's auto-configured {@code RabbitTemplate} picks up this
     * bean automatically — without it, messages would only serialize if
     * they were {@code Serializable}/{@code byte[]}/{@code String}, not
     * arbitrary records.
     *
     * <p><b>Gotcha:</b> {@code Jackson2JsonMessageConverter} is built
     * against classic Jackson 2.x ({@code com.fasterxml.jackson}) — a
     * different major version from the app's own REST-layer Jackson 3.x
     * ({@code tools.jackson}, via {@code spring-boot-starter-jackson}).
     * The two {@code ObjectMapper} types are unrelated classes; the app's
     * bean can't be injected here. This is its own, separately-configured
     * Jackson 2.x instance, with {@link JavaTimeModule} registered so
     * {@code Instant} fields on the message records serialize instead of
     * throwing "type not supported".
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
