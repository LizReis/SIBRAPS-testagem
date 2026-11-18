package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;

/** Proteções operacionais regressivas; fatos legados sintéticos são persistidos na fixture. */
class GroupReservation62Tests extends Historical61Support {
    private static final LocalTime TIME = LocalTime.of(15,0);

    @ParameterizedTest @ValueSource(booleans={true,false})
    void canonicalCanceledPresenceCannotBeIgnoredByGroup(boolean samePatient) throws Exception {
        now(DAY.atTime(9,0));
        tx(() -> {
            var a = seed(patient, professional, SituacaoAtendimento.PRESENTE, DAY, "15:00");
            a.setSituacaoAtendimento(SituacaoAtendimento.CANCELADO);
            appointments.saveAndFlush(a);
            return null;
        });
        var dto = new CriarGrupoDTO("Grupo sintético 6.2C", (samePatient ? colleague : professional).getIdPublico(),
                RecorrenciaGrupo.UNICA, DAY, null, TIME, List.of((samePatient ? patient : other).getIdPublico()));
        refuse61(samePatient ? "E62-C-PRESENCA-PACIENTE" : "E62-C-PRESENCA-PROFISSIONAL",
                () -> http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP), dto));
    }

    @Test void futureEnrollmentAlreadyPresentIsIdempotent() throws Exception {
        var g = session61(colleague, DAY, TIME, false, StatusPresencaGrupo.NAO_REGISTRADA, patient);
        var before = database();
        allow61("E62-C-IDEMPOTENTE", () -> http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP+"/"+g.group()+"/inscricoes-futuras"),
                new InscricaoFuturaGrupoDTO(patient.getIdPublico())));
        assertEquals(before, database());
    }

    @Test void retroactiveEnrollmentMustRollBackWhenFutureSessionConflicts() throws Exception {
        var g = session61(colleague, PAST, SLOT, true, StatusPresencaGrupo.NAO_REGISTRADA);
        tx(() -> {
            var s = new com.pet.buscaativa.entities.SessaoGrupo();
            s.setGrupo(groups61.findById(g.group()).orElseThrow());
            s.setStatus(StatusSessaoGrupo.AGENDADA); s.setDataSessao(DAY); s.setHorario(TIME);
            sessions61.saveAndFlush(s);
            seed(patient, professional, SituacaoAtendimento.AGENDADO, DAY, "15:00");
            return null;
        });
        var dto = new InscricaoRetroativaGrupoDTO(patient.getIdPublico(),
                List.of(new FrequenciaSessaoRetroativaDTO(g.session(), StatusPresencaGrupo.PRESENTE)));
        refuse61("E62-C-RETRO-FUTURA", () -> http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP+"/"+g.group()+"/inscricoes-retroativas"),dto));
    }
}
