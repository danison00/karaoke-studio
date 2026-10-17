package com.karaokestudio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KaraokeStudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(KaraokeStudioApplication.class, args);
    }
}
