package com.datawiki;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DataWikiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataWikiApplication.class, args);
    }
}
