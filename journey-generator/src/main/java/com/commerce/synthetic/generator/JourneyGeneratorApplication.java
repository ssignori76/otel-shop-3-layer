package com.commerce.synthetic.generator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GeneratorProperties.class)
public class JourneyGeneratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(JourneyGeneratorApplication.class, args);
    }
}
