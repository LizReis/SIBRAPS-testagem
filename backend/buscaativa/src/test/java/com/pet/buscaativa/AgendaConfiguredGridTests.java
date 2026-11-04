package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import com.pet.buscaativa.config.AgendaHorarioProperties;
import com.pet.buscaativa.entities.enums.TurnoEnum;

class AgendaConfiguredGridTests {
    @Test void gridUsesConfiguredStartAndIntervalRatherThanClockMultiples() {
        var properties = new AgendaHorarioProperties();
        properties.getHorarios().getManha().setInicio(LocalTime.of(7, 10));
        properties.getHorarios().getManha().setFim(LocalTime.of(8, 0));
        properties.setIntervaloMinutos(20);
        assertEquals(java.util.List.of(LocalTime.of(7, 10), LocalTime.of(7, 30), LocalTime.of(7, 50)), properties.grade(TurnoEnum.MANHA));
    }
    @Test void largeIntervalDoesNotWrapAroundMidnightOrLoopForever() {
        var properties = new AgendaHorarioProperties(); properties.setIntervaloMinutos(1440);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () ->
                assertEquals(java.util.List.of(LocalTime.of(7, 0)), properties.grade(TurnoEnum.MANHA)));
    }
    @Test void zeroIntervalIsRejected() {
        var properties = new AgendaHorarioProperties(); properties.setIntervaloMinutos(0);
        assertThrows(IllegalStateException.class, () -> properties.grade(TurnoEnum.MANHA));
    }
}
