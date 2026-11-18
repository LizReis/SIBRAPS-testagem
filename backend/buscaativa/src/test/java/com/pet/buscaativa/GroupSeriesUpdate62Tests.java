package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;

/** Contraprovas C2; fixtures sintéticas, recusa com snapshot integral. */
class GroupSeriesUpdate62Tests extends Historical61Support {
    private static final LocalTime TIME = LocalTime.of(15,0);
    private Long series(Usuario owner, LocalTime time, List<UUID> people) throws Exception {
        var response=http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP),
                new CriarGrupoDTO("Série sintética C2",owner.getIdPublico(),RecorrenciaGrupo.SEMANAL,
                        DAY,DAY.plusWeeks(2),time,people));
        assertEquals(200,businessStatus(response),response.getContentAsString());
        return json.readTree(response.getContentAsString()).get("id").asLong();
    }
    private AtualizarGrupoTerapeuticoDTO edit(Long id, Usuario owner, LocalDate first, LocalTime time,
            RecorrenciaGrupo recurrence, LocalDate end) {
        return new AtualizarGrupoTerapeuticoDTO("Grupo sintético C2",owner.getIdPublico(),first,time,
                recurrence,end,groups61.findById(id).orElseThrow().getVersion());
    }
    @Test void regenerationMayRetainOldSlotsAndExtendCompatibleSeries() throws Exception {
        Long id=series(colleague,TIME,List.of(patient.getIdPublico(),other.getIdPublico()));
        var before=tx(()->sessions61.findByGrupoOrderByDataSessaoDesc(groups61.findById(id).orElseThrow())
                .stream().map(SessaoGrupo::getId).toList());
        allow61("E62-C2-REGEN-OK",()->http61(put(GROUP+"/"+id),
                edit(id,colleague,DAY,TIME,RecorrenciaGrupo.SEMANAL,DAY.plusWeeks(3))));
        tx(()->{
            var rows=sessions61.findByGrupoOrderByDataSessaoDesc(groups61.findById(id).orElseThrow());
            assertEquals(4,rows.size());
            assertTrue(rows.stream().noneMatch(s->before.contains(s.getId())));
            assertEquals(Set.of(DAY,DAY.plusWeeks(1),DAY.plusWeeks(2),DAY.plusWeeks(3)),
                    new HashSet<>(rows.stream().map(SessaoGrupo::getDataSessao).toList()));
            for(var s:rows) {
                assertEquals(TIME,s.getHorario());
                assertEquals(StatusSessaoGrupo.AGENDADA,s.getStatus());
                assertEquals(Set.of(patient.getIdPublico(),other.getIdPublico()),
                        new HashSet<>(participants61.findPacienteIds(s.getId())));
            }
            return null;
        });
    }
    @Test void regenerationMustNotIgnoreAnotherGroupAtDestination() throws Exception {
        Long id=series(colleague,TIME.plusMinutes(30),List.of(patient.getIdPublico()));
        session61(professional,DAY.plusWeeks(1),TIME,false,StatusPresencaGrupo.NAO_REGISTRADA,patient);
        refuse61("E62-C2-REGEN-GROUP",()->http61(put(GROUP+"/"+id),
                edit(id,colleague,DAY,TIME,RecorrenciaGrupo.SEMANAL,DAY.plusWeeks(2))));
    }
    @Test void regenerationChecksNewCoordinatorEvenWithNoParticipants() throws Exception {
        Long id=series(colleague,TIME.plusMinutes(30),List.of());
        individual61(patient,professional,SituacaoAtendimento.AGENDADO,DAY.plusWeeks(2),"15:00");
        refuse61("E62-C2-REGEN-OWNER",()->http61(put(GROUP+"/"+id),
                edit(id,professional,DAY,TIME,RecorrenciaGrupo.SEMANAL,DAY.plusWeeks(2))));
    }
    @Test void transferPreservesPerformedSessionAndChecksOnlyPendingCommitments() throws Exception {
        var g=session61(colleague,PAST,SLOT,true,StatusPresencaGrupo.PRESENTE,patient);
        tx(()->{
            var s=new SessaoGrupo();
            s.setGrupo(groups61.findById(g.group()).orElseThrow());
            s.setDataSessao(DAY);s.setHorario(TIME);s.setStatus(StatusSessaoGrupo.AGENDADA);
            sessions61.saveAndFlush(s);return null;
        });
        individual61(other,professional,SituacaoAtendimento.PRESENTE,PAST,"08:00");
        var before=jdbc.queryForMap("select * from tb_sessao_grupo where id=?",g.session());
        allow61("E62-C2-TRANSFER-HISTORY",()->http61(put(GROUP+"/"+g.group()),
                edit(g.group(),professional,PAST,SLOT,RecorrenciaGrupo.UNICA,null)));
        assertEquals(before,jdbc.queryForMap("select * from tb_sessao_grupo where id=?",g.session()));
        assertEquals(colleague.getId(),jdbc.queryForObject("select responsavel_atendimento_id from tb_sessao_grupo where id=?",Long.class,g.session()));
        assertEquals(professional.getId(),jdbc.queryForObject("select coordenador_id from tb_grupo_terapeutico where id=?",Long.class,g.group()));
    }
    @Test void canceledSessionDoesNotBlockCompatibleTransfer() throws Exception {
        var g=session61(colleague,DAY,TIME,false,StatusPresencaGrupo.NAO_REGISTRADA);
        tx(()->{
            var s=sessions61.findById(g.session()).orElseThrow();s.setStatus(StatusSessaoGrupo.CANCELADA);
            sessions61.saveAndFlush(s);return null;
        });
        session61(professional,DAY,TIME,false,StatusPresencaGrupo.NAO_REGISTRADA);
        var before=jdbc.queryForMap("select * from tb_sessao_grupo where id=?",g.session());
        allow61("E62-C2-TRANSFER-CANCEL",()->http61(put(GROUP+"/"+g.group()),
                edit(g.group(),professional,DAY,TIME,RecorrenciaGrupo.UNICA,null)));
        assertEquals(before,jdbc.queryForMap("select * from tb_sessao_grupo where id=?",g.session()));
        assertEquals(professional.getId(),jdbc.queryForObject("select coordenador_id from tb_grupo_terapeutico where id=?",Long.class,g.group()));
    }
}
