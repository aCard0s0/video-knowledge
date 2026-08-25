package com.tradinglabs.vidingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VidIngestConsoleApp {

    public static void main(String[] args) {
        SpringApplication.run(VidIngestConsoleApp.class, args);
    }
}
