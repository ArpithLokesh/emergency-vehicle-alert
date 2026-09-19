package com.evas.alerting;

import com.evas.trip.events.PositionUpdatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to every committed position update by re-evaluating the trip's
 * device windows. {@code AFTER_COMMIT} means the position write is already
 * durable by the time this runs; {@code @Async} means the HTTP request that
 * POSTed the position already returned before this even starts — a slow or
 * failing evaluation can never affect position ingestion.
 */
@Component
@RequiredArgsConstructor
public class AlertingListener {

    private final AlertingService alertingService;

    @Async("alertingExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPositionUpdated(PositionUpdatedEvent event) {
        alertingService.evaluate(event.tripId());
    }
}
