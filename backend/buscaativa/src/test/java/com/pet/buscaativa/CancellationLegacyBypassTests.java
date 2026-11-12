package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.pet.buscaativa.entities.enums.*;

class CancellationLegacyBypassTests extends AgendaIntegritySupport {
    @MockitoSpyBean Clock clock;
    static final LocalDate DAY=LocalDate.of(2030,4,10);
    @BeforeEach void beforeSlot(){doReturn(DAY.atTime(9,0).atZone(ZoneId.of("America/Bahia")).toInstant()).when(clock).instant();tx(()->{capacity(professional,DAY,TurnoEnum.TARDE,10);return null;});}
    @ParameterizedTest @CsvSource({"AGENDADO,false","AGENDADO,true","REMARCADO,false","REMARCADO,true"})
    void cs01LegacyCannotRecoverCancellationEvenWithReason(String target,boolean reason) throws Exception {var a=tx(()->seed(patient,professional,SituacaoAtendimento.CANCELADO,DAY,"15:00"));var before=snapshot(patient,a.getId());var req=patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus",target).header("Authorization","Bearer "+tokens.gerarToken(admin));if(reason)req.param("justificativa","Cancelamento sintético conferido no prontuário pela equipe.");var r=mvc.perform(req).andReturn().getResponse();assertEquals(409,businessStatus(r));assertEquals(before,snapshot(patient,a.getId()));}
    @Test void cs03DirectLegacyOverloadsCannotRecoverCancellation(){var a=tx(()->seed(patient,professional,SituacaoAtendimento.CANCELADO,DAY,"15:00"));var before=snapshot(patient,a.getId());authenticate();assertThrows(com.pet.buscaativa.services.exceptions.ConflictException.class,()->service.atualizarStatus(a.getId(),SituacaoAtendimento.AGENDADO,a.getVersion()));assertThrows(com.pet.buscaativa.services.exceptions.ConflictException.class,()->service.atualizarStatus(a.getId(),SituacaoAtendimento.REMARCADO,a.getVersion(),"Cancelamento sintético conferido no prontuário pela equipe."));assertEquals(before,snapshot(patient,a.getId()));}
}
