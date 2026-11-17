package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;

class OfferIntegrity51Tests extends CancellationRegularizationSupport {
    private static final String WEEK = "/api/disponibilidade-config/disponibilidade";
    private static final String EX = "/api/disponibilidade-config/excecoes";
    private int send(String url, Object body) throws Exception {
        return businessStatus(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .header("Authorization", "Bearer " + tokens.gerarToken(admin))
                .contentType("application/json").content(json.writeValueAsString(body))).andReturn().getResponse());
    }
    private Disponibilidade weekly(Usuario u, LocalDate day, TurnoEnum turn, int cap) {
        var d = new Disponibilidade(); d.setUsuario(u); d.setDiaDaSemana(day.getDayOfWeek());
        d.setTurno(turn); d.setCapacidade(cap); return availability.saveAndFlush(d);
    }
    private LocalDate next() { return DAY.plusDays(7); }
    @ParameterizedTest @ValueSource(strings={"day","shift","owner"})
    void weeklyTransferCannotRemoveCoverage(String change) throws Exception {
        var day = next();
        var w = tx(() -> { var d = weekly(professional, day, TurnoEnum.MANHA, 3);
            seed(patient, professional, SituacaoAtendimento.AGENDADO, day,"08:00"); return d; });
        var before = database();
        var dto = new DisponibilidadeDTO(w.getId(), change.equals("owner")?colleague.getIdPublico():professional.getIdPublico(),
                change.equals("day")?day.plusDays(1).getDayOfWeek():day.getDayOfWeek(),
                change.equals("shift")?TurnoEnum.TARDE:TurnoEnum.MANHA, 3);
        assertEquals(409, send(WEEK, dto));
        assertEquals(before, database());
    }
    @Test void weeklyTransferWithDateExceptionAndNoOtherAffectedReservationIsAllowed() throws Exception {
        var day = next();
        var w = tx(() -> { var d = weekly(professional, day, TurnoEnum.MANHA, 5);
            capacity(professional, day, TurnoEnum.MANHA, 2);
            seed(patient, professional, SituacaoAtendimento.AGENDADO, day,"08:00"); return d; });
        int code = send(WEEK, new DisponibilidadeDTO(w.getId(), professional.getIdPublico(), day.plusDays(1).getDayOfWeek(), TurnoEnum.MANHA, 1));
        assertTrue(code >= 200 && code < 300, "status="+code);
        assertEquals(day.plusDays(1).getDayOfWeek(), availability.findById(w.getId()).orElseThrow().getDiaDaSemana());
    }
    @ParameterizedTest @ValueSource(strings={"date","shift","owner"})
    void exceptionTransferCannotRemoveCoverage(String change) throws Exception {
        var day = next();
        var e = tx(() -> { capacity(professional, day, TurnoEnum.MANHA, 3);
            seed(patient, professional, SituacaoAtendimento.AGENDADO, day,"08:00");
            return exceptions.findByUsuarioAndDataAndTurno(professional, day, TurnoEnum.MANHA).orElseThrow(); });
        var before = database();
        var dto = new DisponibilidadeExcecaoDTO(e.getId(), change.equals("owner")?colleague.getIdPublico():professional.getIdPublico(),
                change.equals("date")?day.plusDays(1):day, change.equals("shift")?TurnoEnum.TARDE:TurnoEnum.MANHA, 3);
        assertEquals(409, send(EX, dto)); assertEquals(before, database());
    }
    @Test void institutionalDayProtectsReservationsEvenWhenUtcIsTomorrow() throws Exception {
        // Data controlada anterior à data real: o uso direto de now() ignoraria a reserva.
        // Às 22:00 na Bahia, o instante já pertence ao dia seguinte em UTC.
        var day = LocalDate.of(2000,1,2);
        now(day.atTime(22,0));
        var w = tx(() -> { var d = weekly(professional,day,TurnoEnum.MANHA,3);
            seed(patient,professional,SituacaoAtendimento.AGENDADO,day,"08:00");
            seed(other,professional,SituacaoAtendimento.AGENDADO,day,"09:00"); return d; });
        var before = database();
        assertEquals(409, send(WEEK, new DisponibilidadeDTO(w.getId(),professional.getIdPublico(),day.getDayOfWeek(),TurnoEnum.MANHA,1)));
        assertEquals(before,database());
    }
    @Test void directServiceCannotUseAdministratorEmailWhenActorIsProfessional() throws Exception {
        var w = tx(() -> weekly(colleague,next(),TurnoEnum.MANHA,3));
        var svc = applicationContext.getBean(com.pet.buscaativa.services.DisponibilidadeService.class);
        var before=database();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(professional,null,professional.getAuthorities()));
        try {
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> svc.save(new DisponibilidadeDTO(w.getId(),colleague.getIdPublico(),next().getDayOfWeek(),TurnoEnum.MANHA,4),admin.getEmail()));
            assertEquals(before,database());
        } finally { authenticate(); }
    }
    @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext applicationContext;

    @Test void recoveryVersusExceptionTransferKeepsEffectiveOffer() throws Exception {
        var a = canceled(true);
        var e = exceptions.findByUsuarioAndDataAndTurno(professional,DAY,TurnoEnum.TARDE).orElseThrow();
        var dto = new DisponibilidadeExcecaoDTO(e.getId(),professional.getIdPublico(),DAY.plusDays(1),TurnoEnum.TARDE,10);
        var r = race("E51-recover-move-exception", List.of(()->recover(a),()-> {
            int code = send(EX,dto); return code >= 200 && code < 300 ? 200 : code;
        }));
        assertEquals(new RaceResult(1,1),r);
        if (appointment(a.getId()).getOcupaVaga()) {
            assertTrue(exceptions.findByUsuarioAndDataAndTurno(professional,DAY,TurnoEnum.TARDE).isPresent());
        }
    }
}
