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

/** Remarcações legítimas vs bypass e cadeias incorretas; fixtures sintéticas. */
class ReschedulingIntegrity62Tests extends CancellationRegularizationSupport {
    private AgendamentoDTO sucessor(Agendamento origem, LocalDate quando, String hora, Usuario destino) {
        return new AgendamentoDTO(null, destino.getIdPublico(), null, patient.getIdPublico(), null, null,
                quando, TurnoEnum.TARDE, LocalTime.parse(hora), SituacaoAtendimento.REMARCADO, origem.getId(), null);
    }

    private void recusarCriacao(String caso, Agendamento origem, LocalDate quando, String hora) throws Exception {
        var before=database();
        int resultado=post(sucessor(origem,quando,hora,colleague));
        boolean unchanged=before.equals(database());
        System.out.printf("E62 %s expected=409 actual=%d unchanged=%s%n",caso,resultado,unchanged);
        assertAll(caso,()->assertEquals(409,resultado),()->assertTrue(unchanged));
    }

    @ParameterizedTest @ValueSource(strings={"AGENDADO","REMARCADO","CANCELADO"})
    void substitutedOriginCannotBeReopenedThroughGenericStatus(String target) throws Exception {
        var origem=tx(()->seed(patient,professional,SituacaoAtendimento.FALTOU,DAY.minusDays(1),"08:00"));
        assertEquals(201,post(sucessor(origem,DAY,"15:00",colleague)));
        var atual=appointment(origem.getId());
        assertEquals(SituacaoReserva.SUBSTITUIDA,atual.getSituacaoReserva());
        var before=database();
        var resposta=mvc.perform(patch("/api/agendamentos/"+origem.getId()+"/status")
                .param("novoStatus",target).param("version",atual.getVersion().toString())
                .param("justificativa",REASON).header("Authorization","Bearer "+tokens.gerarToken(admin)))
                .andReturn().getResponse();
        assertEquals(409,businessStatus(resposta),resposta.getContentAsString());
        assertEquals(before,database());
    }

    @Test void successorMustBeStrictlyAfterOriginEvenOnSameDay() throws Exception {
        var origem=tx(()->seed(patient,professional,SituacaoAtendimento.AGENDADO,DAY,"16:00"));
        recusarCriacao("E62-E-ORDER",origem,DAY,"15:00");
    }

    @Test void canceledWithVerifiedAbsenceCannotBeRemappingSource() throws Exception {
        var origem=tx(()->{
            var a=seed(patient,professional,SituacaoAtendimento.CANCELADO,DAY.minusDays(1),"08:00");
            a.registrarFatoEmReservaCancelada(FrequenciaAssistencial.FALTOU);
            return appointments.saveAndFlush(a);
        });
        recusarCriacao("E62-E-CANCELED-ABSENCE",origem,DAY,"15:00");
    }

    @Test void sourceWithoutPredecessorCannotClaimRemarcada() throws Exception {
        var origem=tx(()->seed(patient,professional,SituacaoAtendimento.REMARCADO,DAY.minusDays(1),"08:00"));
        recusarCriacao("E62-E-ORPHAN",origem,DAY,"15:00");
    }

    @Test void sourceWithNonSubstitutedPredecessorIsNotSilentlyAccepted() throws Exception {
        var origem=tx(()->{
            var pai=seed(patient,professional,SituacaoAtendimento.AGENDADO,DAY.minusDays(2),"08:00");
            var filho=seed(patient,professional,SituacaoAtendimento.REMARCADO,DAY.minusDays(1),"08:00");
            filho.setAgendamentoOriginal(pai);
            return appointments.saveAndFlush(filho);
        });
        recusarCriacao("E62-E-INCONSISTENT-PARENT",origem,DAY,"15:00");
    }

    @Test void sourceCannotHideExistingSuccessorByResettingLegacyProjection() throws Exception {
        var origem=tx(()->seed(patient,professional,SituacaoAtendimento.AGENDADO,DAY.minusDays(1),"08:00"));
        assertEquals(201,post(sucessor(origem,DAY,"15:00",colleague)));
        tx(()->{
            var modificada=appointment(origem.getId());
            modificada.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);
            appointments.saveAndFlush(modificada);
            return null;
        });
        recusarCriacao("E62-E-SECOND-SUCCESSOR",appointment(origem.getId()),DAY,"16:00");
    }

    @Test void legitimateSuccessorPreservesOriginalFactAndOwnerChange() throws Exception {
        var origem=tx(()->seed(patient,professional,SituacaoAtendimento.FALTOU,DAY.minusDays(1),"08:00"));
        assertEquals(201,post(sucessor(origem,DAY,"15:00",colleague)));
        var atual=appointment(origem.getId());
        assertEquals(SituacaoReserva.SUBSTITUIDA,atual.getSituacaoReserva());
        assertEquals(FrequenciaAssistencial.FALTOU,atual.getFrequenciaAssistencial());
        assertFalse(atual.getOcupaVaga());
        var filhos=tx(()->appointments.findAll().stream().filter(x->x.getAgendamentoOriginal()!=null
                && x.getAgendamentoOriginal().getId().equals(origem.getId())).toList());
        assertEquals(1,filhos.size());
        var filho=filhos.getFirst();
        assertEquals(SituacaoReserva.REMARCADA,filho.getSituacaoReserva());
        assertEquals(FrequenciaAssistencial.NAO_REGISTRADA,filho.getFrequenciaAssistencial());
        assertTrue(filho.isRemarcacaoAposFalta());
        assertEquals(colleague.getId(),filho.getUsuario().getId());
        assertEquals(patient.getId(),filho.getPaciente().getId());
    }
}
