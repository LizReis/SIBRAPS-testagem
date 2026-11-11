package com.pet.buscaativa;

import java.time.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** Existing regressions test facts after their slot, independently of the hour the suite runs. */
@TestConfiguration(proxyBeanMethods=false)
@ConditionalOnProperty(name="sibraps.test.closed-day-clock",havingValue="true")
class ClosedDayAgendaClock {
    @Bean @Primary Clock closedDayClock(){
        var zone=ZoneId.of("America/Bahia");
        return Clock.fixed(LocalDate.now(ZoneOffset.UTC).atTime(23,59,59).atZone(zone).toInstant(),zone);
    }
}
