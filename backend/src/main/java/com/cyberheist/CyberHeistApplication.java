package com.cyberheist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CyberHeistApplication {

    public static void main(String[] args) {
        SpringApplication.run(CyberHeistApplication.class, args);
    }
}