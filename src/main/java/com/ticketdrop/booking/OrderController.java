package com.ticketdrop.booking;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/** HTTP for buying the seats of a hold. */
@RestController
class OrderController {

    private final BookingService bookings;

    OrderController(BookingService bookings) {
        this.bookings = bookings;
    }

    /** 201 the first time a hold is bought; 200 with the same order on every repeat. */
    @PostMapping("/api/orders")
    ResponseEntity<Order> buy(@Valid @RequestBody BuyRequest request) {
        PurchaseResult result = bookings.buy(request.holdId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.order());
    }
}
