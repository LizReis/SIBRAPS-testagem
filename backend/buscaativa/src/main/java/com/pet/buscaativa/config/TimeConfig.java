package com.pet.buscaativa.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(AgendaTempoProperties.class)
public class TimeConfig {
    @Bean
    Clock clock(AgendaTempoProperties tempo) {
        return Clock.system(tempo.getFuso());
    }
}