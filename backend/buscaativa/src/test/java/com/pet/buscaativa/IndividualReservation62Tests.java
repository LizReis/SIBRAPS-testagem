package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.AgendamentoDTO;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.services.exceptions.ConflictException;

/** Regressão 6.2B: caminho de serviço, recuperação e remarcação com troca de profissional. */
class IndividualReservation62Tests extends Historical61Support {
    private static final LocalTime TIME = LocalTime.of(15,0);
    private AgendamentoDTO booking(Paciente p, Usuario u, LocalTime time, Long original) {
        return new AgendamentoDTO(null,u.getIdPublico(),null,p.getIdPublico(),null,null,DAY,TurnoEnum.TARDE,time,
                original==null?SituacaoAtendimento.AGENDADO:SituacaoAtendimento.REMARCADO,original,null);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void directServiceCannotBypassGroupConflict(boolean patientConflict) {
        session61(patientConflict?colleague:professional,DAY,TIME,false,StatusPresencaGrupo.NAO_REGISTRADA,
                patientConflict?patient:other);
        authenticate(); var before=database();
        assertThrows(ConflictException.class,()->service.save(booking(patient,professional,TIME,null)));
        assertEquals(before,database());
    }
    @Test void transferToAnotherProfessionalCannotReplaceOriginalOnConflict() throws Exception {
        var original=service.save(booking(patient,professional,TIME.plusMinutes(30),null));
        session61(colleague,DAY,TIME,false,StatusPresencaGrupo.NAO_REGISTRADA,other);
        refuse61("E62-B-TRANSFER",()->http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos"),
                booking(patient,colleague,TIME,original.id())));
        assertEquals(SituacaoReserva.AGENDADA,appointment(original.id()).getSituacaoReserva());
        assertFalse(appointments.existsByAgendamentoOriginal_Id(original.id()));
    }
    @Test void recoveryPreservesStructuredConflictWithoutDisclosingAnotherPatient() throws Exception {
        var a=canceled(true);
        session61(professional,DAY,TIME,false,StatusPresencaGrupo.NAO_REGISTRADA,other);
        var before=database(); var response=recover(admin,a,recovery(a));
        assertEquals(409,businessStatus(response),response.getContentAsString());
        assertEquals("CONFLITO_ASSISTENCIAL_PENDENTE_RECONCILIACAO",json.readTree(response.getContentAsString()).get("codigo").asText());
        assertFalse(response.getContentAsString().contains(other.getNome()));
        assertEquals(before,database());
    }
    @Test void groupWithoutParticipantsStillPreventsIndividualReservation() throws Exception {
        session61(professional,DAY,TIME,false,StatusPresencaGrupo.NAO_REGISTRADA);
        refuse61("E62-B-EMPTY",()->http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos"),
                booking(patient,professional,TIME,null)));
    }
}
