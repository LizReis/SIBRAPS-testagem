package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.services.PacienteService;
import tools.jackson.databind.JsonNode;

/** BA-01..14: HTTP/JPA, actual domain corrections, RF22 and unit boundaries. */
class BuscaAtivaSelectionTests extends AgendaIntegritySupport {
    @Autowired PacienteService clinical;
    private void state(int misses, boolean visit, boolean pending) {
        tx(() -> { var p=patients.findById(patient.getId()).orElseThrow();
            p.setCountFaltas(misses); p.setClassificacaoRisco(misses>=3?ClassificacaoRisco.VERMELHO:misses>=2?ClassificacaoRisco.AMARELO:ClassificacaoRisco.VERDE);
            p.setGatilhoVisitaAcionado(visit); p.setIndicadoresPendentes(pending); patients.saveAndFlush(p); return null; });
    }
    private JsonNode list() throws Exception {
        var r=mvc.perform(get("/api/pacientes/busca-ativa").header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse();
        assertEquals(200,r.getStatus()); return json.readTree(r.getContentAsString());
    }
    private JsonNode entry(JsonNode result) {
        for(var n:result) if(patient.getIdPublico().toString().equals(n.get("idPublico").asText())) return n;
        return null;
    }
    @ParameterizedTest @CsvSource({"3,false,true,VERMELHO","2,true,true,AMARELO","2,false,false,AMARELO",
            "0,true,true,VERDE","0,false,false,VERDE","3,true,true,VERMELHO"})
    void ba01To06RiskAndConfirmedVisitAreIndependent(int misses,boolean visit,boolean expected,ClassificacaoRisco risk) throws Exception {
        state(misses,visit,false); var result=list(); assertEquals(expected,entry(result)!=null);
        assertEquals(expected?1:0,java.util.stream.StreamSupport.stream(result.spliterator(),false)
                .filter(n->patient.getIdPublico().toString().equals(n.get("idPublico").asText())).count());
        var p=patients.findById(patient.getId()).orElseThrow();assertEquals(risk,p.getClassificacaoRisco());assertEquals(misses,p.getCountFaltas());
    }
    private Agendamento chain() {
        return tx(() -> {
            var original=seed(patient,professional,SituacaoAtendimento.FALTOU,LocalDate.now().minusDays(8),"08:00");
            original.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);appointments.saveAndFlush(original);
            var replacement=seed(patient,professional,SituacaoAtendimento.FALTOU,LocalDate.now().minusDays(6),"08:00");
            replacement.setAgendamentoOriginal(original);replacement.setRemarcacaoAposFalta(true);appointments.saveAndFlush(replacement);
            clinical.recalcularAssiduidadePaciente(patients.findById(patient.getId()).orElseThrow());return replacement;
        });
    }
    private void correct(Agendamento a,SituacaoAtendimento status) throws Exception {
        assertEquals(200,mvc.perform(patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus",status.name())
            .header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse().getStatus());
    }
    @Test void ba07ConfirmedAlertDisappearsAfterCorrection() throws Exception {
        var a=chain();assertNotNull(entry(list()));correct(a,SituacaoAtendimento.PRESENTE);assertNull(entry(list()));
        var p=patients.findById(patient.getId()).orElseThrow();assertFalse(p.getGatilhoVisitaAcionado());assertEquals(0,p.getCountFaltas());
    }
    private void address(boolean street,String reference) {
        tx(()->{var p=patients.findById(patient.getId()).orElseThrow();var e=new Endereco();if(!street){e.setLogradouro("Rua sintética");e.setNumero("10");e.setBairro("Bairro sintético");e.setCidade("Cidade sintética");e.setEstado("BA");}e.setComplemento(reference);
            p.setEndereco(e);p.setSituacaoRua(street);patients.saveAndFlush(p);return null;});
    }
    @Test void ba08StreetUsesSocialReference() throws Exception {
        state(2,true,false);address(true,"Equipamento social sintético");var n=entry(list());assertNotNull(n);
        assertEquals("Busca em ponto de referência necessária",n.get("alerta").asText());assertEquals("Equipamento social sintético",n.get("localBusca").asText());
    }
    @Test void ba09StreetWithoutReferenceUsesExactWarning() throws Exception {
        state(2,true,false);address(true,null);var n=entry(list());assertNotNull(n);assertTrue(n.get("localBusca").isNull());
        assertEquals("Nenhum ponto de referência cadastrado — verificar com a equipe antes de acionar a busca.",n.get("avisoAdicional").asText());
    }
    @Test void ba10FixedAddressUsesHomeVisit() throws Exception {
        state(2,true,false);address(false,null);var n=entry(list());assertNotNull(n);
        assertEquals("Visita domiciliar necessária",n.get("alerta").asText());assertEquals("Rua sintética, 10, Bairro sintético, Cidade sintética, BA",n.get("localBusca").asText());
    }
    @Test void ba11ForeignUnitNeverLeaksAddresses() throws Exception {
        state(2,true,false);address(false,null);tx(()->{var p=patients.findById(patient.getId()).orElseThrow();p.setCapsReferencia(CapsEnum.CAPS_I);patients.saveAndFlush(p);return null;});
        var body=list();assertNull(entry(body));assertFalse(body.toString().contains("Rua sintética"));
    }
    @Test void ba12InactiveNeverIncluded() throws Exception {
        state(3,true,false);tx(()->{var p=patients.findById(patient.getId()).orElseThrow();p.setStatusPaciente(StatusPaciente.INATIVO);patients.saveAndFlush(p);return null;});assertNull(entry(list()));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void ba13ProvisionalIndicatorsRemainVisibleOnlyAsReview(boolean oldAlert) throws Exception {
        state(0,oldAlert,true);address(false,null);var n=entry(list());assertNotNull(n);
        assertEquals("Revisão do histórico necessária",n.get("alerta").asText());assertTrue(n.get("localBusca").isNull());
        assertTrue(n.get("avisoAdicional").asText().contains("não estão confirmados"));assertTrue(patients.findById(patient.getId()).orElseThrow().getIndicadoresPendentes());
    }
    @Test void ba14RetroactiveCorrectionChangesListWithoutChangingBookingCount() throws Exception {
        var a=chain();long rows=appointments.count();correct(a,SituacaoAtendimento.PRESENTE);assertNull(entry(list()));
        correct(a,SituacaoAtendimento.FALTOU);var n=entry(list());assertNotNull(n);assertEquals("AMARELO",n.get("classificacaoRisco").asText());assertEquals(2,n.get("faltasConsecutivas").asInt());assertEquals(rows,appointments.count());
    }
    @Test void panelIncludesGreenConfirmedVisitWithoutChangingDistribution() throws Exception {
        state(0,true,false);tx(()->{patients.findAll().stream().filter(p->!p.getId().equals(patient.getId())).forEach(p->{p.setStatusPaciente(StatusPaciente.INATIVO);patients.save(p);});return null;});var r=mvc.perform(get("/api/painel/resumo").header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse();assertEquals(200,r.getStatus());
        var body=json.readTree(r.getContentAsString());assertEquals(0,body.get("distribuicaoClassificacao").get("vermelhos").asInt());
        assertTrue(java.util.stream.StreamSupport.stream(body.get("pacientesPrioritarios").spliterator(),false).anyMatch(n->patient.getIdPublico().toString().equals(n.get("idPublico").asText())));
        assertEquals(ClassificacaoRisco.VERDE,patients.findById(patient.getId()).orElseThrow().getClassificacaoRisco());
    }
    @ParameterizedTest @CsvSource({"true,false,true","true,true,false","false,false,false"})
    void reportIncludesOnlyConfirmedVisitOutsideRedRisk(boolean visit,boolean pending,boolean expected) throws Exception {
        state(0,visit,pending);var response=mvc.perform(get("/api/relatorios/busca-ativa/excel")
                .header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse();
        assertEquals(200,response.getStatus());StringBuilder cells=new StringBuilder();
        try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(response.getContentAsByteArray()))) {
            for(var sheet:workbook)for(var row:sheet)for(var cell:row)cells.append(cell.toString()).append(' ');
        }
        assertEquals(expected,cells.toString().contains(patient.getNome()));
        assertEquals(ClassificacaoRisco.VERDE,patients.findById(patient.getId()).orElseThrow().getClassificacaoRisco());
    }

}
