package com.sprinters.mediscribe;

import com.sprinters.mediscribe.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class MediScribeApplication {
    public static void main(String[] args) {
        SpringApplication.run(MediScribeApplication.class, args);
    }
}
