package com.ticketdrop.booking;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ticketdrop.config.BookingProperties;
import com.ticketdrop.event.EventService;
import com.ticketdrop.web.InvalidRequestException;

import jakarta.validation.Valid;

/**
 * HTTP for holding and releasing seats. It checks the request and hands over to whichever
 * {@link BookingService} is configured; it knows nothing about locking.
 */
@RestController
class HoldController {

    private final BookingService bookings;
    private final EventService events;
    private final BookingProperties properties;

    HoldController(BookingService bookings, EventService events, BookingProperties properties) {
        this.bookings = bookings;
        this.events = events;
        this.properties = properties;
    }

    @PostMapping("/api/events/{eventId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    Hold hold(@PathVariable long eventId, @Valid @RequestBody HoldRequest request) {
        List<Long> seatIds = request.seatIds();
        // Invalid input is rejected before an unknown event: that is the order the spec gives.
        if (seatIds.size() > properties.maxSeatsPerHold()) {
            throw new InvalidRequestException(
                    "seatIds may name at most " + properties.maxSeatsPerHold() + " seats");
        }
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new InvalidRequestException("seatIds must not name the same seat twice");
        }
        events.requireEvent(eventId);
        return bookings.hold(eventId, request.buyerId(), seatIds);
    }

    @DeleteMapping("/api/holds/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void release(@PathVariable UUID holdId) {
        bookings.release(holdId);
    }
}
