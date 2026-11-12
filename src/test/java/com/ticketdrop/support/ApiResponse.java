package com.ticketdrop.support;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An HTTP response from the service: the status and the raw body, with accessors for fields of a
 * JSON body. Asking for a field that is not there fails the test with the whole body in the message.
 */
public record ApiResponse(int status, String rawBody) {

    private static final JsonMapper JSON = JsonMapper.shared();

    public String text(String field) {
        return field(field).asString();
    }

    public int intValue(String field) {
        return field(field).asInt();
    }

    public long longValue(String field) {
        return field(field).asLong();
    }

    public UUID uuid(String field) {
        return UUID.fromString(field(field).asString());
    }

    public Instant instant(String field) {
        return Instant.parse(field(field).asString());
    }

    public List<Long> longs(String field) {
        return field(field).valueStream().map(JsonNode::asLong).toList();
    }

    /** Reads a body that is a JSON array of seats, as returned by the seat listing. */
    public List<SeatRow> seats() {
        return json().valueStream()
                .map(seat -> new SeatRow(
                        seat.get("id").asLong(),
                        seat.get("row").asInt(),
                        seat.get("number").asInt(),
                        seat.get("status").asString()))
                .toList();
    }

    private JsonNode field(String name) {
        JsonNode value = json().get(name);
        if (value == null || value.isNull()) {
            throw new AssertionError("No field '" + name + "' in the response (status " + status + "): " + rawBody);
        }
        return value;
    }

    private JsonNode json() {
        if (rawBody == null || rawBody.isBlank()) {
            throw new AssertionError("Expected a JSON body, but the response (status " + status + ") was empty");
        }
        return JSON.readTree(rawBody);
    }
}
