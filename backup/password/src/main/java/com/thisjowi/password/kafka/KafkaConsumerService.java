package com.thisjowi.password.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.thisjowi.password.Utils.JwtUtil;

@Service
public class KafkaConsumerService {
    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerService.class);

    private final JwtUtil jwtUtil;

    public KafkaConsumerService(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @KafkaListener(topics = "auth-events", groupId = "password-service-group")
    public void listen(String message) {
        log.debug("Received message from Kafka topic 'auth-events'");
    }
}

