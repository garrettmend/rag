package com.example.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the multi-tenant RAG service.
 * It boots the Spring application context and component scanning for every package under com.example.rag.
 * Scheduling is enabled so the ingestion worker can poll its queue on a fixed delay.
 */
@SpringBootApplication
@EnableScheduling
public class RagApplication {

    /**
     * Starts the Spring Boot application.
     *
     * @param args command-line arguments passed through to Spring
     */
    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
