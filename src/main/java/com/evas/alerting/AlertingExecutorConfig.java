package com.evas.alerting;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The bounded pool that runs alerting's {@code @Async} listeners
 * ({@code @Async("alertingExecutor")}), separate from Spring's default
 * async executor so alerting can never starve or be starved by unrelated
 * async work.
 *
 * <p>Bounded on purpose: {@link ThreadPoolExecutor.CallerRunsPolicy} means
 * that once the queue is full, submitting a new alerting task runs it on
 * the caller's own thread (the position-ingestion request thread) instead
 * of being dropped or throwing. That's deliberate backpressure — if
 * alerting genuinely can't keep up, position ingestion slows down rather
 * than either losing an alert or the executor's queue growing without
 * bound under a burst of position updates.
 */
@Configuration
@RequiredArgsConstructor
public class AlertingExecutorConfig {

    private final AlertingProperties properties;

    @Bean(name = "alertingExecutor")
    public Executor alertingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.executor().corePoolSize());
        executor.setMaxPoolSize(properties.executor().maxPoolSize());
        executor.setQueueCapacity(properties.executor().queueCapacity());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("alerting-");
        executor.initialize();
        return executor;
    }
}
