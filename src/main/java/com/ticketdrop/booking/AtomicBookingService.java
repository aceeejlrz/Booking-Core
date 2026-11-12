package com.ticketdrop.booking;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketdrop.config.BookingProperties;

/**
 * The safe implementation of the booking rules, and the default strategy.
 *
 * <p>Not written yet. The three methods are placeholders until Task 7 of the implementation plan,
 * where they are written against section 6 of the design spec.
 */
@Service
@ConditionalOnProperty(name = "ticketdrop.booking-strategy", havingValue = "atomic", matchIfMissing = true)
public class AtomicBookingService implements BookingService {

    private final JdbcClient jdbc;
    private final BookingProperties properties;

    public AtomicBookingService(JdbcClient jdbc, BookingProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    @Transactional
    public Hold hold(long eventId, UUID buyerId, List<Long> seatIds) {
        throw new UnsupportedOperationException("Implemented in Task 7");
    }

    @Override
    @Transactional
    public void release(UUID holdId) {
        throw new UnsupportedOperationException("Implemented in Task 7");
    }

    @Override
    @Transactional
    public PurchaseResult buy(UUID holdId) {
        throw new UnsupportedOperationException("Implemented in Task 7");
    }
}
