package com.collectivelaunchpad.agentclient;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.collectivelaunchpad.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP client for the Python agent service. Requests and responses are JSON trees in the
 * agent's snake_case format; the agent owns those shapes (see agent/app/schemas.py).
 */
@Service
public class AgentClient {

    /** The agent service is down, or Claude could not answer. The message is safe to show the admin. */
    public static class AgentException extends RuntimeException {
        public AgentException(String message) {
            super(message);
        }
    }

    private final RestClient http;
    private final ObjectMapper mapper;

    public AgentClient(AppProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        // HTTP/1.1: the JDK client otherwise tries an h2c upgrade, which uvicorn does not support.
        HttpClient jdk = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        var factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofMinutes(10)); // a turn can run several Claude calls
        this.http = RestClient.builder()
                .baseUrl(props.agentUrl())
                .requestFactory(factory)
                .defaultHeader("X-Internal-Token", props.internalToken())
                .build();
    }

    public JsonNode onboardingTurn(JsonNode request) {
        return post("/onboarding/turn", request);
    }

    public JsonNode classify(JsonNode request) {
        return post("/enquiries/classify", request);
    }

    public JsonNode draftReply(JsonNode request) {
        return post("/enquiries/draft-reply", request);
    }

    public JsonNode health() {
        return http.get().uri("/health").retrieve().body(JsonNode.class);
    }

    private JsonNode post(String path, JsonNode body) {
        for (int attempt = 1; ; attempt++) {
            try {
                return http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
            } catch (RestClientResponseException e) {
                throw new AgentException(detail(e));
            } catch (ResourceAccessException e) {
                // Connection problems (agent restarting, not up yet): one retry after a short pause.
                if (attempt == 1 && !(e.getMostSpecificCause() instanceof java.net.http.HttpTimeoutException)) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new AgentException("Interrupted while contacting the agent service.");
                    }
                    continue;
                }
                Throwable cause = e.getMostSpecificCause();
                String why = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                throw new AgentException("The agent service is not reachable (" + why + "). Try again in a moment.");
            }
        }
    }

    private String detail(RestClientResponseException e) {
        try {
            JsonNode node = mapper.readTree(e.getResponseBodyAsString());
            if (node.hasNonNull("detail")) {
                return node.get("detail").isTextual() ? node.get("detail").asText() : node.get("detail").toString();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "Agent service error " + e.getStatusCode().value() + ".";
    }
}
