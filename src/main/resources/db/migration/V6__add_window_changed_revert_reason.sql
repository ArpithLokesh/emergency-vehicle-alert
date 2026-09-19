-- Adds a revert reason for a signal falling out of the tracked window for
-- a reason other than a normal pass — a route deviation dropped it, or it
-- was deactivated mid-approach. Discovered needed while implementing the
-- alerting engine's evaluate() flow (Alerting-3).

ALTER TABLE signal_preemption DROP CONSTRAINT signal_preemption_revert_reason_valid;
ALTER TABLE signal_preemption ADD CONSTRAINT signal_preemption_revert_reason_valid
    CHECK (revert_reason IS NULL OR revert_reason IN
        ('PASSED', 'TIMEOUT', 'TRIP_TERMINATED', 'TRIP_PAUSED', 'WINDOW_CHANGED'));
