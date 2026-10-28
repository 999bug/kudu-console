package com.kuducrud;

import com.kuducrud.config.KuduProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(KuduProperties.class)
@EnableScheduling
public class KuduConsoleApplication {

    public static void main(String[] args) {
        SpringApplication.run(KuduConsoleApplication.class, args);
    }
}
