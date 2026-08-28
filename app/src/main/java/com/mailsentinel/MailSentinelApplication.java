package com.mailsentinel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main application entrypoint for MailSentinel phishing detection service.
 */
@SpringBootApplication
public class MailSentinelApplication {

    public static void main(String[] args) {
        SpringApplication.run(MailSentinelApplication.class, args);
    }
}
