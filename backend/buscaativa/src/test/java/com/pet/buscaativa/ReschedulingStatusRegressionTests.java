package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletResponse;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.*;

/** Regressão do endpoint legado; datas passadas isolam a guarda temporal. */
class ReschedulingStatusRegressionTests extends CancellationRegularizationSupport {
    private MockHttpServletResponse status(Usuario actor, Agendamento a, SituacaoAtendimento desired) throws Exception {
        return mvc.perform(patch("/api/agendamentos/" + a.getId() + "/status")
                .param("novoStatus", desired.name()).param("version", a.getVersion().toString())
                .param("justificativa", REASON).header("Authorization", "Bearer " + tokens.gerarToken(actor)))
                .andReturn().getResponse();
    }

    @ParameterizedTest
    @CsvSource({"RM-02,AGENDADO,REMARCADO", "RM-03,AGENDADO,REMARCADO_ORIGEM",
            "RM-04,FALTOU,REMARCADO_ORIGEM", "RM-05,PRESENTE,REMARCADO_ORIGEM"})
    void legacyMustNotManufactureRescheduling(String id, SituacaoAtendimento initial,
            SituacaoAtendimento desired) throws Exception {
        var a = tx(() -> {
            var row = seed(patient, professional, SituacaoAtendimento.AGENDADO, DAY.minusDays(2), "08:00");
            histories.registrarAgendamento(row);
            return row;
        });
        if (initial != SituacaoAtendimento.AGENDADO) {
            var setup = status(professional, a, initial);
            assertEquals(200, businessStatus(setup), "Fixture de frequência: " + setup.getContentAsString());
            a = appointment(a.getId());
        }
        assertEquals(initial, a.getSituacaoAtendimento());
        assertNull(a.getAgendamentoOriginal());
        assertFalse(appointments.existsByAgendamentoOriginal_Id(a.getId()));
        var before = database();
        var response = status(admin, a, desired);
        int actual = businessStatus(response);
        var after = appointment(a.getId());
        boolean unchanged = before.equals(database());
        boolean successor = appointments.existsByAgendamentoOriginal_Id(a.getId());
        System.out.printf("E61 %s expected=409 actual=%d unchanged=%s initial=%s final=%s reserve=%s frequency=%s occupies=%s successor=%s%n",
                id, actual, unchanged, initial, after.getSituacaoAtendimento(), after.getSituacaoReserva(),
                after.getFrequenciaAssistencial(), after.getOcupaVaga(), successor);
        assertAll(id,
                () -> assertEquals(409, actual, "Resposta: " + response.getContentAsString()),
                () -> assertTrue(unchanged, "A rejeição deve preservar os fatos originais e todas as tabelas públicas."));
    }
}
