package com.evas.alerting;

import com.evas.trip.events.TripTerminatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Reacts to a trip stopping (for good or for now) by reverting/clearing whatever alerting was tracking for it. */
@Component
@RequiredArgsConstructor
public class RevertListener {

    private final AlertingService alertingService;

    @Async("alertingExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTripTerminated(TripTerminatedEvent event) {
        alertingService.handleTripReset(event.tripId(), event.reason());
    }
}
