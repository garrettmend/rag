package com.example.rag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test for the Spring application context.
 * It boots the full application configuration, including data sources and AWS clients.
 * The test fails if any bean cannot be created or wired.
 */
@SpringBootTest
class RagApplicationTests {

    /**
     * Verifies that the application context starts successfully.
     */
    @Test
    void contextLoads() {
    }
}
