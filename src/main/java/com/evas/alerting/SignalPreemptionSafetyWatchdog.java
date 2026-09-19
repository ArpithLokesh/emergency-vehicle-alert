package com.evas.alerting;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The belt-and-braces backstop for signal preemptions: no matter what went
 * wrong elsewhere — a crashed async listener, a bug in {@code evaluate}'s
 * pass-detection, a vehicle whose feed dies in some way that skips the
 * normal STALE/EXPIRED lifecycle — nothing should ever hold a signal
 * force-green forever. Every ACTIVE preemption gets reverted after
 * {@code alerting.safety-timeout} regardless of anything else.
 *
 * <p>Same shape as {@code TripStaleWatchdog}: an orchestrator that isn't
 * itself transactional, delegating each row to a separate bean
 * ({@code AlertingService}) so its {@code @Transactional} actually applies,
 * with a per-row try/catch so one bad row can't abort the sweep.
 *
 * <p>Backend-side safety is {@code alerting.safety-timeout} (~120s);
 * devices are expected to carry their own hardware-level auto-revert at a
 * longer timeout (~180s) as a second, independent backstop — standard
 * practice for real traffic-signal preemption systems, and the reason
 * "publish inside the transaction, log-and-continue on failure" is an
 * acceptable RabbitMQ failure policy rather than needing a full outbox
 * pattern: a signal is never solely dependent on this backend successfully
 * delivering a revert.
 */
@Component
@RequiredArgsConstructor
public class SignalPreemptionSafetyWatchdog {

    private static final Logger log = LoggerFactory.getLogger(SignalPreemptionSafetyWatchdog.class);

    private final SignalPreemptionRepository signalPreemptionRepository;
    private final AlertingService alertingService;
    private final AlertingProperties properties;

    @Scheduled(fixedDelayString = "${alerting.sweep-interval}")
    public void sweep() {
        Instant cutoff = Instant.now().minus(properties.safetyTimeout());
        List<SignalPreemption> stuck = signalPreemptionRepository
                .findByStateAndForceGreenAtBefore(SignalPreemptionState.ACTIVE, cutoff);
        for (SignalPreemption preemption : stuck) {
            try {
                alertingService.revertStuckPreemption(preemption.getId());
            } catch (Exception e) {
                log.warn("Failed to revert stuck preemption {}, will retry next sweep", preemption.getId(), e);
            }
        }
        if (!stuck.isEmpty()) {
            log.info("Safety-timeout sweep reverted {} preemption(s) stuck ACTIVE past {}",
                    stuck.size(), properties.safetyTimeout());
        }
    }

    /**
     * Purely informational — logs how many preemptions are ACTIVE at
     * startup (i.e. survived from before a restart). Doesn't revert
     * anything itself; {@link #sweep} already does that on its normal
     * cadence regardless of whether the backend just started or has been
     * running for days, so there's nothing extra to trigger here.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logOrphanedPreemptionsOnStartup() {
        long activeCount = signalPreemptionRepository.countByState(SignalPreemptionState.ACTIVE);
        if (activeCount > 0) {
            log.info("{} signal preemption(s) already ACTIVE at startup (from before a restart) — "
                    + "the safety-timeout sweep will revert any that are stuck", activeCount);
        }
    }
}
