package com.thisjowi.note.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Service
public class AuthenticationClient {

    private final WebClient authenticationWebClient;
    private final Logger log = LoggerFactory.getLogger(AuthenticationClient.class);

    @Autowired
    public AuthenticationClient(WebClient authenticationWebClient) {
        this.authenticationWebClient = authenticationWebClient;
    }

    public boolean validateToken(String token) {
        Long id = getUserIdFromToken(token);
        return id != null && id != -1L;
    }

    public Long getUserIdFromToken(String token) {
        String headerValue = token != null && token.startsWith("Bearer ") ? token : ("Bearer " + token);
        log.debug("Calling Authentication service /user endpoint to validate token");

        Mono<Long> mono = authenticationWebClient.get()
                .uri("/v1/auth/user")
                .header(HttpHeaders.AUTHORIZATION, headerValue)
                .exchangeToMono((ClientResponse resp) -> {
                    int statusCode = resp.statusCode().value();
                    log.debug("Auth service response status: {}", statusCode);

                    if (statusCode >= 300 && statusCode < 400) {
                        log.warn("Auth service returned redirect {}", statusCode);
                        return Mono.just(-1L);
                    }

                    if (statusCode >= 200 && statusCode < 300) {
                        return resp.bodyToMono(Map.class)
                                .map(body -> {
                                    Object userIdObj = body.get("userId");
                                    if (userIdObj instanceof Number) {
                                        long userId = ((Number) userIdObj).longValue();
                                        log.debug("Successfully extracted userId: {}", userId);
                                        return userId;
                                    }
                                    log.warn("Auth service returned body without valid userId: {}", body);
                                    return -1L;
                                });
                    } else {
                        return resp.bodyToMono(String.class)
                                .defaultIfEmpty("(no body)")
                                .flatMap(body -> {
                                    log.warn("Auth service returned {} with body: {}", statusCode, body);
                                    return Mono.just(-1L);
                                });
                    }
                })
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(e -> {
                    log.error("Error calling auth service to get user id", e);
                    return Mono.just(-1L);
                });

        try {
            Long result = mono.block();
            return result != null ? result : -1L;
        } catch (Exception e) {
            log.error("Error blocking for user id", e);
            return -1L;
        }
    }
}
