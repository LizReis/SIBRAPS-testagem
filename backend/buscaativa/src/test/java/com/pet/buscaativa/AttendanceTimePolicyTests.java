package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.pet.buscaativa.config.*;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.services.PoliticaTemporalFrequencia;
import com.pet.buscaativa.services.exceptions.*;

class AttendanceTimePolicyTests {
    private static final ZoneId BAHIA=ZoneId.of("America/Bahia");
    @ParameterizedTest @CsvSource({
        "2030-04-09T15:00,2030-04-10T09:00,true,true",
        "2029-01-01T15:00,2030-04-10T09:00,true,true",
        "2030-04-10T15:00,2030-04-10T14:59,true,false",
        "2030-04-10T15:00,2030-04-10T15:00,true,false",
        "2030-04-10T15:00,2030-04-10T15:01,true,true",
        "2030-04-09T23:30,2030-04-10T00:10,true,true",
        "2030-04-11T08:00,2030-04-10T22:00,true,false",
        "2030-04-10T15:00,2030-04-10T14:59,false,false",
        "2030-04-10T15:00,2030-04-10T15:00,false,true",
        "2030-04-10T15:00,2030-04-10T15:01,false,true"})
    void officialLocalBoundaryIsSharedByIndividualsAndGroups(String scheduled,String now,boolean correction,boolean accepted){
        var when=LocalDateTime.parse(scheduled);var fixed=Clock.fixed(LocalDateTime.parse(now).atZone(BAHIA).toInstant(),ZoneId.of("Pacific/Auckland"));
        var policy=new PoliticaTemporalFrequencia(fixed,new AgendaTempoProperties());var a=new Agendamento();a.setId(42L);a.setDataAgendamento(when.toLocalDate());a.setHoraAtendimento(when.toLocalTime());var s=new SessaoGrupo();s.setId(7L);s.setDataSessao(when.toLocalDate());s.setHorario(when.toLocalTime());
        if(accepted){assertDoesNotThrow(()->policy.validarAgendamento(a,correction));assertDoesNotThrow(()->policy.validarSessao(s,correction));}
        else{assertThrows(ValidationException.class,()->policy.validarAgendamento(a,correction));assertThrows(ValidationException.class,()->policy.validarSessao(s,correction));}
    }
    @Test void utcMidnightDoesNotPrematurelyAdvanceBahiaDate(){var p=new PoliticaTemporalFrequencia(Clock.fixed(Instant.parse("2030-04-11T00:10:00Z"),ZoneOffset.UTC),new AgendaTempoProperties());var a=new Agendamento();a.setDataAgendamento(LocalDate.of(2030,4,11));a.setHoraAtendimento(LocalTime.MIDNIGHT);assertThrows(ValidationException.class,()->p.validarAgendamento(a,false));a.setDataAgendamento(LocalDate.of(2030,4,10));a.setHoraAtendimento(LocalTime.of(21,0));assertDoesNotThrow(()->p.validarAgendamento(a,true));}
    @Test void missingOrAmbiguousLegacyTimeIsNeverInvented(){var p=new PoliticaTemporalFrequencia(Clock.fixed(Instant.parse("2030-04-10T20:00:00Z"),ZoneOffset.UTC),new AgendaTempoProperties());var a=new Agendamento();a.setId(42L);assertEquals("HORARIO_ASSISTENCIAL_PENDENTE_RECONCILIACAO",assertThrows(PendenciaFrequenciaException.class,()->p.validarAgendamento(a,true)).getCodigo());a.setDataAgendamento(LocalDate.of(2011,10,16));a.setHoraAtendimento(LocalTime.of(0,30));assertThrows(PendenciaFrequenciaException.class,()->p.validarAgendamento(a,true));a.setDataAgendamento(LocalDate.of(2012,2,25));a.setHoraAtendimento(LocalTime.of(23,30));assertThrows(PendenciaFrequenciaException.class,()->p.validarAgendamento(a,true));}
    private ApplicationContextRunner config(){return new ApplicationContextRunner().withUserConfiguration(TimeConfig.class);}
    @Test void hostTimezoneDoesNotChooseInstitutionalClock(){var previous=TimeZone.getDefault();try{TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));config().run(c->{assertNull(c.getStartupFailure());assertEquals(BAHIA,c.getBean(Clock.class).getZone());});}finally{TimeZone.setDefault(previous);}}
    @Test void explicitTimezoneOverrideIsTypedAndInvalidZoneFailsStartup(){config().withPropertyValues("agenda.tempo.fuso=UTC").run(c->{assertNull(c.getStartupFailure());assertEquals(ZoneId.of("UTC"),c.getBean(Clock.class).getZone());});config().withPropertyValues("agenda.tempo.fuso=Invalid/Institution").run(c->assertNotNull(c.getStartupFailure()));}
}
