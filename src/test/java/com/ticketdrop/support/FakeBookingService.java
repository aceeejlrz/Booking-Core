package com.ticketdrop.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ticketdrop.booking.BookingService;
import com.ticketdrop.booking.Hold;
import com.ticketdrop.booking.PurchaseResult;

/**
 * A scripted BookingService for testing the HTTP layer on its own. A test tells it what the next
 * call should return or throw, and can ask afterwards what it was called with.
 */
public class FakeBookingService implements BookingService {

    private Hold holdResult;
    private RuntimeException holdFailure;
    private PurchaseResult buyResult;
    private RuntimeException buyFailure;
    private final List<String> calls = new ArrayList<>();
    private List<Long> lastHoldSeatIds;
    private UUID lastReleasedHoldId;

    public void holdReturns(Hold hold) {
        this.holdResult = hold;
        this.holdFailure = null;
    }

    public void holdThrows(RuntimeException failure) {
        this.holdFailure = failure;
    }

    public void buyReturns(PurchaseResult result) {
        this.buyResult = result;
        this.buyFailure = null;
    }

    public void buyThrows(RuntimeException failure) {
        this.buyFailure = failure;
    }

    /** Every call received since the last reset: "hold", "release" or "buy", in order. */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    public List<Long> lastHoldSeatIds() {
        return lastHoldSeatIds;
    }

    public UUID lastReleasedHoldId() {
        return lastReleasedHoldId;
    }

    public void reset() {
        holdResult = null;
        holdFailure = null;
        buyResult = null;
        buyFailure = null;
        calls.clear();
        lastHoldSeatIds = null;
        lastReleasedHoldId = null;
    }

    @Override
    public Hold hold(long eventId, UUID buyerId, List<Long> seatIds) {
        calls.add("hold");
        lastHoldSeatIds = List.copyOf(seatIds);
        if (holdFailure != null) {
            throw holdFailure;
        }
        return holdResult;
    }

    @Override
    public void release(UUID holdId) {
        calls.add("release");
        lastReleasedHoldId = holdId;
    }

    @Override
    public PurchaseResult buy(UUID holdId) {
        calls.add("buy");
        if (buyFailure != null) {
            throw buyFailure;
        }
        return buyResult;
    }
}
