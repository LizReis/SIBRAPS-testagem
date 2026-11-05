package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.nio.file.Files;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.TokenService;
import com.pet.buscaativa.services.impl.SusApiAdapter;
import com.pet.buscaativa.migration.AgendaMigrationTool;
import tools.jackson.databind.ObjectMapper;

/** Boot validates and serves an actually migrated file, never Hibernate create/update. */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(MigratedAgendaHttpTests.MigratedDatabase.class)
class MigratedAgendaHttpTests extends SecurityTestSupport {
    @TestConfiguration static class MigratedDatabase {
        @Bean DataSource dataSource() throws Exception {
            var source = LegacyMigrationFixture.legacy(Files.createTempDirectory("sibraps-migrated-http-").resolve("legacy"));
            AgendaMigrationTool.migrar(source);
            return source;
        }
    }
    @MockitoBean SusApiAdapter sus;
    @Autowired DataSource source;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UsuarioRepository users;
    @Autowired PacienteRepository patients;
    @Autowired AgendamentoRepository appointments;
    @Autowired TokenService tokens;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    private Usuario admin() { return users.findAll().stream().filter(u -> u.getTipoUsuario() == TipoUsuario.ADMINISTRADOR).findFirst().orElseThrow(); }
    private String token() { return tokens.gerarToken(admin()); }

    @Test void migratedRemarcacaoHasCompatibleLegacyAndCanonicalResponse() throws Exception {
        var response = mvc.perform(get("/api/agendamentos/3").header("Authorization", "Bearer " + token())).andReturn().getResponse();
        assertEquals(200, response.getStatus()); var body = json.readTree(response.getContentAsString());
        assertEquals("REMARCADO", body.get("situacaoAtendimento").asText());
        assertEquals("REMARCADA", body.get("situacaoReserva").asText());
        assertEquals("NAO_REGISTRADA", body.get("frequenciaAssistencial").asText());
        assertEquals(2, body.get("agendamentoOriginalId").asLong());
    }

    @Test void migratedUnknownFrequencyRemainsNullAndCannotBeChangedViaLegacyPatch() throws Exception {
        var response = mvc.perform(get("/api/agendamentos/6").header("Authorization", "Bearer " + token())).andReturn().getResponse();
        assertEquals(200, response.getStatus()); var body = json.readTree(response.getContentAsString());
        assertEquals("CANCELADO", body.get("situacaoAtendimento").asText());
        assertTrue(body.get("migracaoPendente").asBoolean()); assertTrue(body.get("frequenciaAssistencial").isNull());
        var columns = LegacyMigrationFixture.columns(source); var before = LegacyMigrationFixture.snapshot(source, columns);
        response = mvc.perform(patch("/api/agendamentos/6/status").param("novoStatus", "PRESENTE")
                .header("Authorization", "Bearer " + token())).andReturn().getResponse();
        assertEquals(409, response.getStatus()); assertEquals(before, LegacyMigrationFixture.snapshot(source, columns));
    }

    @Test void migratedVersionAndClinicalCorrectionWorkWithPreservedHiddenAbsences() throws Exception {
        var original = appointments.findById(4L).orElseThrow();
        var riskBefore = patients.findById(1L).orElseThrow().getClassificacaoRisco();
        assertEquals(7, original.getVersion());
        var response = mvc.perform(patch("/api/agendamentos/4/status").param("novoStatus", "FALTOU").param("version", "7")
                .header("Authorization", "Bearer " + token())).andReturn().getResponse();
        assertEquals(200, response.getStatus()); var body = json.readTree(response.getContentAsString());
        assertEquals("FALTOU", body.get("situacaoAtendimento").asText());
        assertEquals("FALTOU", body.get("frequenciaAssistencial").asText()); assertEquals(8, body.get("version").asInt());
        var p = patients.findById(1L).orElseThrow();
        assertEquals(5, p.getCountFaltas()); assertEquals(java.time.LocalDate.of(2025, 12, 29), p.getDataUltimaPresenca());
        assertTrue(p.getIndicadoresPendentes());
        // Row 12 has unknown later attendance: the legacy false flag must not become a confirmed alert.
        assertFalse(p.getGatilhoVisitaAcionado()); assertEquals(riskBefore, p.getClassificacaoRisco());
        var patientResponse = mvc.perform(get("/api/pacientes/" + p.getIdPublico())
                .header("Authorization", "Bearer " + token())).andReturn().getResponse();
        assertEquals(200, patientResponse.getStatus());
        assertTrue(json.readTree(patientResponse.getContentAsString()).get("indicadoresPendentes").asBoolean());
        var panel = mvc.perform(get("/api/painel/resumo").header("Authorization", "Bearer " + token())).andReturn().getResponse();
        assertEquals(200, panel.getStatus());
        assertTrue(panel.getContentAsString().contains("Revisão do histórico necessária"));
        assertFalse(panel.getContentAsString().contains("Visita domiciliar necessária"));
        var columns = LegacyMigrationFixture.columns(source); var before = LegacyMigrationFixture.snapshot(source, columns);
        response = mvc.perform(patch("/api/agendamentos/4/status").param("novoStatus", "PRESENTE").param("version", "7")
                .header("Authorization", "Bearer " + token())).andReturn().getResponse();
        assertEquals(409, response.getStatus()); assertEquals(before, LegacyMigrationFixture.snapshot(source, columns));
    }

    @Test void migratedClinicalFactsRetainReceptionAndUnitBarriers() throws Exception {
        var reception = new Usuario(); reception.setNome("Recepção sintética"); reception.setEmail(java.util.UUID.randomUUID()+"@test.local");
        reception.setSenha("synthetic-not-for-login"); reception.setTipoUsuario(TipoUsuario.RECEPCAO); reception.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD);
        users.saveAndFlush(reception);
        var foreign = new Usuario(); foreign.setNome("Unidade sintética"); foreign.setEmail(java.util.UUID.randomUUID()+"@test.local");
        foreign.setSenha("synthetic-not-for-login"); foreign.setTipoUsuario(TipoUsuario.ADMINISTRADOR); foreign.setUnidadeAtuacao(UnidadeAtuacao.CAPS_I);
        users.saveAndFlush(foreign);
        var columns = LegacyMigrationFixture.columns(source); var before = LegacyMigrationFixture.snapshot(source, columns);
        var response = mvc.perform(patch("/api/agendamentos/7/status").param("novoStatus", "PRESENTE")
                .header("Authorization", "Bearer " + tokens.gerarToken(reception))).andReturn().getResponse();
        assertEquals(422, response.getStatus());
        response = mvc.perform(patch("/api/agendamentos/7/status").param("novoStatus", "PRESENTE")
                .header("Authorization", "Bearer " + tokens.gerarToken(foreign))).andReturn().getResponse();
        assertEquals(404, response.getStatus()); assertEquals(before, LegacyMigrationFixture.snapshot(source, columns));
    }

    @Test void oldEnversRevisionRemainsReadableWithoutBackfilledClinicalFields() {
        var old = new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status ->
                org.hibernate.envers.AuditReaderFactory.get(entityManager).find(com.pet.buscaativa.entities.Agendamento.class, 4L, 2));
        assertEquals(SituacaoAtendimento.PRESENTE, old.getSituacaoAtendimento());
        assertNull(old.getFrequenciaAssistencial()); assertNull(old.getSituacaoReserva());
        assertNull(old.getOcupaVaga());
    }
}
