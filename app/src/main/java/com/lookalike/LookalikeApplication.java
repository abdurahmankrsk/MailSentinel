package com.lookalike;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main application entrypoint for Lookalike phishing detection service.
 */
@SpringBootApplication
public class LookalikeApplication {

    public static void main(String[] args) {
        SpringApplication.run(LookalikeApplication.class, args);
    }
}
