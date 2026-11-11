package com.example.reporttest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point.  The web-application type is disabled in
 * application.properties (spring.main.web-application-type=none) because this
 * is a plain UI-automation test and no HTTP server is needed.
 */
@SpringBootApplication
public class ReportTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportTestApplication.class, args);
    }
}