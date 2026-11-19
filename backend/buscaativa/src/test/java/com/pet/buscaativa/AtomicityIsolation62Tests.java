package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.services.GrupoTerapeuticoService;
import com.pet.buscaativa.services.HistoricoPacienteService;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;

/** F3A: rollback depois de gravações reais; 401/403/404; sigilo de terceiros. */
class AtomicityIsolation62Tests extends Historical61Support {
    @Autowired GrupoTerapeuticoService groupService;
    @MockitoSpyBean HistoricoPacienteService historicoSpy;

    private CorrigirFrequenciasGrupoDTO correction(GroupRef61 s) {
        return new CorrigirFrequenciasGrupoDTO(
                List.of(frequency61(patient,StatusPresencaGrupo.PRESENTE)),s.version(),REASON);
    }

    @Test
    void retroactiveEnrollmentFailureAfterSecondRealHistoryWriteRollsBackWholeDatabase() throws Exception {
        var first=session61(professional,PAST.minusDays(1),SLOT,true,StatusPresencaGrupo.PRESENTE,other);
        long nextId=tx(()->{
            var s=new SessaoGrupo();
            s.setGrupo(groups61.findById(first.group()).orElseThrow());
            s.setDataSessao(PAST);
            s.setHorario(SLOT);
            s.setStatus(StatusSessaoGrupo.AGENDADA);
            s.confirmarRealizacao(professional);
            return sessions61.saveAndFlush(s).getId();
        });
        var before=database();
        var calls=new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation->{
            invocation.callRealMethod();
            if(calls.incrementAndGet()==2)
                throw new IllegalStateException("synthetic failure after second real clinical history write");
            return null;
        }).when(historicoSpy).registrarFrequenciaGrupo(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());

        var dto=new InscricaoRetroativaGrupoDTO(patient.getIdPublico(),List.of(
                new FrequenciaSessaoRetroativaDTO(first.session(),StatusPresencaGrupo.PRESENTE),
                new FrequenciaSessaoRetroativaDTO(nextId,StatusPresencaGrupo.PRESENTE)));
        authenticate();
        assertThrows(IllegalStateException.class,()->groupService.inscreverRetroativamente(first.group(),dto));
        assertEquals(2,calls.get(),"A falha deve ocorrer após duas gravações reais no histórico");
        assertEquals(before,database(),
                "Sessões, participantes, histórico, auditorias, indicadores e versões devem retornar ao snapshot anterior");
    }

    @Test
    void anonymousHistoricalCorrectionIs401AndDoesNotMutateDatabase() throws Exception {
        var g=session61(professional,PAST,SLOT,true,StatusPresencaGrupo.FALTOU,patient);
        var before=database();
        var response=mvc.perform(patch(GROUP+"/sessoes/"+g.session()+"/frequencias")
                .contentType("application/json").content(json.writeValueAsString(correction(g))))
                .andReturn().getResponse();
        assertEquals(401,businessStatus(response),response.getContentAsString());
        assertEquals(before,database());
    }

    @ParameterizedTest
    @CsvSource({"colleague,403","reception,403","foreign,404"})
    void unauthorizedGroupCorrectionCannotRevealOrChangeClinicalFacts(String role,int expected) throws Exception {
        var g=session61(professional,PAST,SLOT,true,StatusPresencaGrupo.FALTOU,patient);
        var actor=actor(role);
        var before=database();
        var response=mvc.perform(patch(GROUP+"/sessoes/"+g.session()+"/frequencias")
                .header("Authorization","Bearer "+tokens.gerarToken(actor))
                .contentType("application/json").content(json.writeValueAsString(correction(g))))
                .andReturn().getResponse();
        assertEquals(expected,businessStatus(response),response.getContentAsString());
        assertEquals(before,database());
        assertFalse(response.getContentAsString().contains(REASON),
                "Justificativa clínica não pode retornar ao ator sem autorização");
    }

    @ParameterizedTest
    @ValueSource(strings={"colleague","reception"})
    void directGroupServiceMustNotBypassAuthorization(String role) throws Exception {
        var g=session61(professional,PAST,SLOT,true,StatusPresencaGrupo.FALTOU,patient);
        var actor=actor(role);
        var before=database();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(actor,null,actor.getAuthorities()));
        assertThrows(AccessDeniedException.class,
                ()->groupService.corrigirFrequencias(g.session(),correction(g)));
        assertEquals(before,database());
    }

    @Test
    void operationalConflictMustNotExposeOtherPatientIdentity() throws Exception {
        session61(professional,DAY,LocalTime.of(15,0),false,
                StatusPresencaGrupo.NAO_REGISTRADA,other);
        var before=database();
        var response=http61(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos"),
                new AgendamentoDTO(null,professional.getIdPublico(),null,patient.getIdPublico(),
                        null,null,DAY,TurnoEnum.TARDE,LocalTime.of(15,0),
                        SituacaoAtendimento.AGENDADO,null,null));
        assertEquals(409,businessStatus(response),response.getContentAsString());
        assertEquals(before,database());
        String body=response.getContentAsString();
        assertFalse(body.contains(other.getIdPublico().toString()),
                "Conflitos não devem expor UUID de paciente terceiro");
        assertFalse(body.contains(other.getNome()),
                "Conflitos não devem expor nome de paciente terceiro");
    }
}
