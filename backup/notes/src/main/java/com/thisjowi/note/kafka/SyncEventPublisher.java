package com.thisjowi.note.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class SyncEventPublisher {

    private static final String TOPIC = "sync-events";
    private static final String SERVICE_NAME = "note";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void publish(String userId, String action, Map<String, Object> payload) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", UUID.randomUUID().toString());
            event.put("userId", userId);
            event.put("serviceName", SERVICE_NAME);
            event.put("action", action);
            event.put("payload", payload);
            event.put("timestamp", Instant.now().toEpochMilli());

            String message = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(TOPIC, userId, message);
        } catch (Exception e) {
            System.err.println("Failed to publish sync event: " + e.getMessage());
        }
    }
}
