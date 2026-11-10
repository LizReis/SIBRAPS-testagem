package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.migration.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.*;
import tools.jackson.databind.ObjectMapper;

class RiskVisitMigratedHistoryTests {
    @TempDir Path temp;
    @Test void a08UnknownMigratedFrequencyRemainsReviewWithoutInventedClinicalEvidence() throws Exception {
        var source=LegacyMigrationFixture.legacy(temp.resolve("unknown-risk"));var jdbc=new JdbcTemplate(source);
        // Explicitly reproduce an unconfirmed old flag before migrating the actual legacy SQL schema.
        jdbc.update("update tb_paciente set classificacao_risco=1,gatilho_visita=true where id=1");AgendaMigrationTool.migrar(source);
        var facts=jdbc.queryForList("select id,situacao_atendimento,situacao_reserva,frequencia_assistencial,migracao_pendente,version from tb_agendamento order by id");long history=jdbc.queryForObject("select count(*) from tb_historico_paciente",Long.class);
        CorrectiveMigrationApplication.application(source).run(context->{
            assertThat(context).hasNotFailed();var p=context.getBean(PacienteRepository.class).findById(1L).orElseThrow();assertTrue(p.getIndicadoresPendentes());
            var actor=context.getBean(UsuarioRepository.class).findAll().stream().filter(u->u.getTipoUsuario()==TipoUsuario.ADMINISTRADOR).findFirst().orElseThrow();var token=context.getBean(TokenService.class).gerarToken(actor);
            var mvc=MockMvcBuilders.webAppContextSetup((WebApplicationContext)context.getSourceApplicationContext()).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();var json=context.getBean(ObjectMapper.class);
            var r=mvc.perform(get("/api/pacientes/busca-ativa").header("Authorization","Bearer "+token)).andReturn().getResponse();assertEquals(200,r.getStatus());var row=java.util.stream.StreamSupport.stream(json.readTree(r.getContentAsString()).spliterator(),false).filter(n->p.getIdPublico().toString().equals(n.get("idPublico").asText())).findFirst().orElseThrow();
            assertEquals("Revisão do histórico necessária",row.get("alerta").asText());assertTrue(row.get("localBusca").isNull());
            r=mvc.perform(post("/api/pacientes/"+p.getIdPublico()+"/alertas/visualizacoes").header("Authorization","Bearer "+token).contentType("application/json").content("{\"idRequisicao\":\""+UUID.randomUUID()+"\"}")).andReturn().getResponse();assertEquals(201,r.getStatus(),r.getContentAsString());
            assertFalse(jdbc.queryForObject("select gatilho_confirmado from tb_auditoria_busca_ativa where tipo_evento='VISUALIZACAO_ALERTA'",Boolean.class));
        });
        assertEquals(facts,jdbc.queryForList("select id,situacao_atendimento,situacao_reserva,frequencia_assistencial,migracao_pendente,version from tb_agendamento order by id"));assertEquals(history,jdbc.queryForObject("select count(*) from tb_historico_paciente",Long.class));assertNull(jdbc.queryForObject("select frequencia_assistencial from tb_agendamento where id=12",String.class));
    }
}
