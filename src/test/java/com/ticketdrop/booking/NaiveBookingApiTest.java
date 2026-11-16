package com.ticketdrop.booking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/** Runs the whole booking contract against the naive strategy. */
@TestPropertySource(properties = "ticketdrop.booking-strategy=naive")
@ExtendWith(OutputCaptureExtension.class)
class NaiveBookingApiTest extends BookingApiContract {

    @Test
    void warnsAtStartupThatItIsUnsafe(CapturedOutput output) {
        assertThat(output).contains("NAIVE booking strategy is enabled");
    }
}
