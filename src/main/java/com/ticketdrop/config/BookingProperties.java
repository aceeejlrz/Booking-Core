package com.ticketdrop.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The service's settings, under the prefix {@code ticketdrop}. The defaults live here, so they hold
 * even with an empty configuration, and a nonsensical value stops the service at startup.
 *
 * @param holdDuration    how long a hold lasts
 * @param maxSeatsPerHold the most seats one hold may take
 * @param bookingStrategy which implementation of the booking rules runs
 */
@ConfigurationProperties("ticketdrop")
public record BookingProperties(
        @DefaultValue("5m") Duration holdDuration,
        @DefaultValue("4") int maxSeatsPerHold,
        @DefaultValue("atomic") BookingStrategy bookingStrategy) {

    public BookingProperties {
        if (holdDuration.isZero() || holdDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "ticketdrop.hold-duration must be positive, but was " + holdDuration);
        }
        if (maxSeatsPerHold < 1) {
            throw new IllegalArgumentException(
                    "ticketdrop.max-seats-per-hold must be at least 1, but was " + maxSeatsPerHold);
        }
    }
}
