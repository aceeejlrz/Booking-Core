package com.ticketdrop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ticketdrop.support.ApiClient;
import com.ticketdrop.support.ApiResponse;
import com.ticketdrop.support.IntegrationTest;

@IntegrationTest
class SmokeTest {

    @Autowired
    ApiClient api;

    @Test
    void serviceStartsAndReportsHealthy() {
        ApiResponse r = api.send("GET", "/actuator/health", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo("UP");
    }

    @Test
    void testsRunOutsideUtc() {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Kolkata");
    }
}
