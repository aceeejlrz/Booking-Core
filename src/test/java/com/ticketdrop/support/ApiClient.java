package com.ticketdrop.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/**
 * Calls the running service over real HTTP. Thread-safe: race tests share one instance between
 * hundreds of concurrent callers. A 4xx or 5xx is returned as a response, never thrown.
 */
public final class ApiClient {

    private static final Set<String> METHODS_WITH_A_BODY = Set.of("POST", "PUT", "PATCH");

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl;

    public ApiClient(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    /**
     * Sends one request. {@code jsonBody} may be null: a POST, PUT or PATCH then goes out with the
     * JSON content type and an empty body, which is how a client "forgets" the body.
     */
    public ApiResponse send(String method, String path, String jsonBody) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60));
        if (METHODS_WITH_A_BODY.contains(method)) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody));
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new ApiResponse(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(method + " " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during " + method + " " + path, e);
        }
    }
}
