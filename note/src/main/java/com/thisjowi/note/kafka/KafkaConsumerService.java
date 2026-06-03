package com.thisjowi.note.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class KafkaConsumerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerService.class);

    public static final String LAST_TOKEN = null;

    @KafkaListener(topics = "auth-events", groupId = "notes-service-group")
    public void listen(String message) {
        log.debug("Token received from Kafka topic auth-events");
    }
}
