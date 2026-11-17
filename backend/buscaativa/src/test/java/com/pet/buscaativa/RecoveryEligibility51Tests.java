package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.pet.buscaativa.entities.enums.*;

class RecoveryEligibility51Tests extends CancellationRegularizationSupport {
    @ParameterizedTest
    @EnumSource(value=StatusPaciente.class, names="ATIVO", mode=EnumSource.Mode.EXCLUDE)
    void nonActivePatientCannotRecoverAppointment(StatusPaciente status) throws Exception {
        var a = canceled(true);
        tx(() -> { var p = patients.findById(patient.getId()).orElseThrow();
            p.setStatusPaciente(status); patients.saveAndFlush(p); return null; });
        var before = database();
        assertEquals(422, recover(admin, a, recovery(a)).getStatus());
        assertEquals(before, database());
    }

    @ParameterizedTest
    @EnumSource(value=StatusPaciente.class, names="ATIVO", mode=EnumSource.Mode.EXCLUDE)
    void historicalFactAndCorrectionDoNotReactivatePatient(StatusPaciente status) throws Exception {
        var a = canceled(false);
        tx(() -> { var p = patients.findById(patient.getId()).orElseThrow();
            p.setStatusPaciente(status); patients.saveAndFlush(p); return null; });
        assertEquals(200, factual(admin, a, fact(a, FrequenciaAssistencial.PRESENTE)).getStatus());
        assertCanceledFact(a, FrequenciaAssistencial.PRESENTE);
        assertEquals(200, correct(admin, appointment(a.getId()), FrequenciaAssistencial.FALTOU));
        assertCanceledFact(a, FrequenciaAssistencial.FALTOU);
        assertEquals(status, patients.findById(patient.getId()).orElseThrow().getStatusPaciente());
    }

    @Test void changedRoleBlocksOnlyOperationalRecovery() throws Exception {
        var future = canceled(true);
        var past = canceled(false);
        tx(() -> { var u = users.findById(professional.getId()).orElseThrow();
            u.setTipoUsuario(TipoUsuario.RECEPCAO); users.saveAndFlush(u); return null; });
        var before = database();
        assertEquals(422, recover(admin, future, recovery(future)).getStatus());
        assertEquals(before, database());
        assertEquals(200, factual(admin, past, fact(past, FrequenciaAssistencial.PRESENTE)).getStatus());
        assertCanceledFact(past, FrequenciaAssistencial.PRESENTE);
        assertEquals(200, correct(admin, appointment(past.getId()), FrequenciaAssistencial.FALTOU));
    }

    @Test void inactivationEndpointPreventsRecovery() throws Exception {
        var a = canceled(true);
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/pacientes/"+patient.getIdPublico())
                .header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse();
        assertEquals(204,response.getStatus());
        var before=database();
        assertEquals(422,recover(admin,a,recovery(a)).getStatus());
        assertEquals(before,database());
    }

    @Test void reactivatedPatientCanRecoverIfOfferStillExists() throws Exception {
        var a = canceled(true);
        tx(() -> { var p = patients.findById(patient.getId()).orElseThrow();
            p.setStatusPaciente(StatusPaciente.INATIVO); patients.saveAndFlush(p); return null; });
        assertEquals(422, recover(admin, a, recovery(a)).getStatus());
        tx(() -> { var p = patients.findById(patient.getId()).orElseThrow();
            p.setStatusPaciente(StatusPaciente.ATIVO); patients.saveAndFlush(p); return null; });
        assertEquals(200, recover(admin, a, recovery(a)).getStatus());
    }
}
