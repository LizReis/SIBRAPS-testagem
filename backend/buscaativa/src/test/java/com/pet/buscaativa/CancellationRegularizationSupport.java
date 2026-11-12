package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.mock.web.MockHttpServletResponse;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.*;

abstract class CancellationRegularizationSupport extends AgendaIntegritySupport {
    static final LocalDate DAY=LocalDate.of(2030,4,10);
    static final String REASON="Cancelamento e resultado conferidos no prontuário sintético pelo responsável.";
    @MockitoSpyBean Clock clock;
    @Autowired AuditoriaRegularizacaoRepository regularizations;
    @Autowired AuditoriaCorrecaoRepository corrections;
    @Autowired HistoricoPacienteService histories;
    @Autowired PacienteService clinical;
    @BeforeEach void regularizationClock(){now(DAY.atTime(9,0));tx(()->{for(var u:List.of(professional,colleague)){capacity(u,DAY,TurnoEnum.MANHA,10);capacity(u,DAY,TurnoEnum.TARDE,10);}return null;});}
    @Override void capacity(Usuario u,LocalDate day,TurnoEnum shift,int size){var c=exceptions.findByUsuarioAndDataAndTurno(u,day,shift).orElseGet(DisponibilidadeExcecao::new);c.setUsuario(u);c.setData(day);c.setTurno(shift);c.setCapacidade(size);exceptions.saveAndFlush(c);}
    void now(LocalDateTime value){doReturn(value.atZone(ZoneId.of("America/Bahia")).toInstant()).when(clock).instant();}
    Agendamento canceled(boolean future){return tx(()->{var a=seed(patient,professional,SituacaoAtendimento.CANCELADO,future?DAY:DAY.minusDays(5),future?"15:00":"08:00");if(future)a.setTurnoAgendamento(TurnoEnum.TARDE);appointments.saveAndFlush(a);histories.registrarAgendamento(a);return a;});}
    Usuario actor(String role){return switch(role){case "owner"->professional;case "admin"->admin;case "colleague"->colleague;case "foreign"->tx(()->user(TipoUsuario.ADMINISTRADOR,UnidadeAtuacao.CAPS_I));case "reception"->tx(()->user(TipoUsuario.RECEPCAO,UnidadeAtuacao.CAPS_AD));default->throw new IllegalArgumentException();};}
    Object database(){var ds=Objects.requireNonNull(jdbc.getDataSource());return LegacyMigrationFixture.snapshot(ds,LegacyMigrationFixture.columns(ds));}
    MockHttpServletResponse recover(Usuario actor,Agendamento a,Object dto) throws Exception{return request(actor,a,"recuperar-reserva",dto);}
    MockHttpServletResponse factual(Usuario actor,Agendamento a,Object dto) throws Exception{return request(actor,a,"frequencia",dto);}
    MockHttpServletResponse request(Usuario actor,Agendamento a,String operation,Object dto) throws Exception {return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos/"+a.getId()+"/regularizacao-cancelamento/"+operation).header("Authorization","Bearer "+tokens.gerarToken(actor)).contentType("application/json").content(json.writeValueAsString(dto))).andReturn().getResponse();}
    RecuperarReservaCanceladaDTO recovery(Agendamento a){return new RecuperarReservaCanceladaDTO(a.getVersion(),REASON);}
    RegularizarFrequenciaCanceladaDTO fact(Agendamento a,FrequenciaAssistencial result){return new RegularizarFrequenciaCanceladaDTO(result,a.getVersion(),REASON);}
    int recover(Agendamento a) throws Exception{return businessStatus(recover(professional,a,recovery(a)));}
    int factual(Agendamento a,FrequenciaAssistencial result) throws Exception{return businessStatus(factual(professional,a,fact(a,result)));}
    int correct(Usuario actor,Agendamento a,FrequenciaAssistencial result) throws Exception{return businessStatus(mvc.perform(patch("/api/agendamentos/"+a.getId()+"/frequencia/correcao").header("Authorization","Bearer "+tokens.gerarToken(actor)).contentType("application/json").content(json.writeValueAsString(new CorrigirFrequenciaDTO(result,a.getVersion(),REASON)))).andReturn().getResponse());}
    void assertCanceledFact(Agendamento a,FrequenciaAssistencial f){var current=appointment(a.getId());assertEquals(SituacaoReserva.CANCELADA,current.getSituacaoReserva());assertEquals(SituacaoAtendimento.CANCELADO,current.getSituacaoAtendimento());assertEquals(f,current.getFrequenciaAssistencial());assertFalse(current.getOcupaVaga());}
    Agendamento legitimateRescheduledCancellation() throws Exception {var original=tx(()->seed(patient,professional,SituacaoAtendimento.AGENDADO,DAY.minusDays(1),"08:00"));var r=mvc.perform(patch("/api/agendamentos/"+original.getId()+"/status").param("novoStatus","FALTOU").header("Authorization","Bearer "+tokens.gerarToken(professional))).andReturn().getResponse();assertEquals(200,r.getStatus());var dto=new AgendamentoDTO(null,professional.getIdPublico(),null,patient.getIdPublico(),null,null,DAY,TurnoEnum.TARDE,LocalTime.of(15,0),SituacaoAtendimento.REMARCADO,original.getId(),null);r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos").header("Authorization","Bearer "+tokens.gerarToken(admin)).contentType("application/json").content(json.writeValueAsString(dto))).andReturn().getResponse();assertEquals(201,r.getStatus(),r.getContentAsString());var a=appointment(json.readTree(r.getContentAsString()).get("id").asLong());r=mvc.perform(patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus","CANCELADO").header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse();assertEquals(200,r.getStatus());return appointment(a.getId());}
}
