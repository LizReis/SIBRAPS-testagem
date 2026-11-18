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

/** Diagnóstico opt-in antes de integrar grupos; pode falhar na base 6.1. */
class AgendaSeries62Characterization extends Historical61Support {
    private static final LocalTime FUTURE = LocalTime.of(15,0);
    private CriarGrupoDTO serie(Usuario u, LocalTime h, List<UUID> participantes) {
        return new CriarGrupoDTO("Série sintética 6.2",u.getIdPublico(),RecorrenciaGrupo.SEMANAL,
                DAY,DAY.plusWeeks(2),h,participantes);
    }
    private Long criarSerie(Usuario u, LocalTime h, List<UUID> participantes) throws Exception {
        var r=http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP),serie(u,h,participantes));
        assertEquals(200,businessStatus(r),r.getContentAsString());
        return json.readTree(r.getContentAsString()).get("id").asLong();
    }
    private void reserva(Paciente p, Usuario u, LocalDate date, LocalTime h) throws Exception {
        tx(()->{capacity(u,date,TurnoEnum.TARDE,10);return null;});
        var dto=new AgendamentoDTO(null,u.getIdPublico(),null,p.getIdPublico(),null,null,date,TurnoEnum.TARDE,h,
                SituacaoAtendimento.AGENDADO,null,null);
        var r=http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos"),dto);
        assertEquals(201,businessStatus(r),r.getContentAsString());
    }
    @ParameterizedTest @ValueSource(ints={0,1,2})
    void sr01WholeSeriesMustRejectParticipantConflictOnAnyDate(int week) throws Exception {
        reserva(patient,professional,DAY.plusWeeks(week),FUTURE);
        refuse61("E62-SR-01-"+week,()->http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP),serie(colleague,FUTURE,List.of(other.getIdPublico(),patient.getIdPublico()))));
    }
    @Test void sr02WholeSeriesMustRejectCoordinatorConflictOnLastDateEvenWithoutParticipants() throws Exception {
        reserva(patient,professional,DAY.plusWeeks(2),FUTURE);
        refuse61("E62-SR-02",()->http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP),serie(professional,FUTURE,List.of())));
    }
    @Test void sr03RegenerationMustRejectParticipantConflictAndPreserveOldSeries() throws Exception {
        var group=criarSerie(colleague,FUTURE.plusMinutes(30),List.of(patient.getIdPublico()));
        reserva(patient,professional,DAY.plusWeeks(1),FUTURE);
        var version=groups61.findById(group).orElseThrow().getVersion();
        var dto=new AtualizarGrupoTerapeuticoDTO("Série sintética 6.2",colleague.getIdPublico(),DAY,FUTURE,
                RecorrenciaGrupo.SEMANAL,DAY.plusWeeks(2),version);
        refuse61("E62-SR-03",()->http61(put(GROUP+"/"+group),dto));
    }
    @Test void sr04CompatibleSeriesRemainsPossible() throws Exception {
        var group=criarSerie(colleague,FUTURE,List.of(patient.getIdPublico(),other.getIdPublico()));
        tx(()->{
            var rows=sessions61.findByGrupoOrderByDataSessaoDesc(groups61.findById(group).orElseThrow());
            assertEquals(3,rows.size());
            assertTrue(rows.stream().allMatch(s->s.getParticipantes().size()==2));
            return null;
        });
    }
    @Test void sr05TransferMustCheckLaterSessionNotOnlyFirst() throws Exception {
        var group=criarSerie(colleague,FUTURE,List.of(other.getIdPublico()));
        reserva(patient,professional,DAY.plusWeeks(2),FUTURE);
        var version=groups61.findById(group).orElseThrow().getVersion();
        var dto=new AtualizarGrupoTerapeuticoDTO("Série sintética 6.2",professional.getIdPublico(),DAY,FUTURE,
                RecorrenciaGrupo.SEMANAL,DAY.plusWeeks(2),version);
        refuse61("E62-SR-05",()->http61(put(GROUP+"/"+group),dto));
    }
    @Test void sr06NextEmptySessionMustRespectCoordinator() throws Exception {
        var group=session61(professional,DAY,FUTURE,false,StatusPresencaGrupo.NAO_REGISTRADA);
        reserva(patient,professional,DAY.plusWeeks(1),FUTURE);
        var dto=new NovaSessaoDTO(group.group(),DAY.plusWeeks(1),FUTURE,List.of());
        refuse61("E62-SR-06",()->http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP+"/sessoes"),dto));
    }
}
