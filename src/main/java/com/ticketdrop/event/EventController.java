package com.ticketdrop.event;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/events")
class EventController {

    private final EventService events;

    EventController(EventService events) {
        this.events = events;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Event create(@Valid @RequestBody CreateEventRequest request) {
        return events.create(request.name(), request.rows(), request.seatsPerRow());
    }

    @GetMapping("/{eventId}/seats")
    List<SeatView> seats(@PathVariable long eventId) {
        return events.seats(eventId);
    }

    @GetMapping("/{eventId}/summary")
    EventSummary summary(@PathVariable long eventId) {
        return events.summary(eventId);
    }
}
