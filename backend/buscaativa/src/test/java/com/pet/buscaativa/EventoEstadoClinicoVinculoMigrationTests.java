package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.dto.EncerramentoPacienteDTO;
import com.pet.buscaativa.entities.enums.MotivoEncerramento;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.migration.AgendaMigrationTool;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.impl.EstadoClinicoLocalEnsaioService;
import com.pet.buscaativa.services.impl.EvidenciaStatusVinculo.Situacao;
import com.pet.buscaativa.services.impl.InicioAcompanhamentoLocalEnsaioService;
import com.pet.buscaativa.services.impl.ProjecaoClinicaVinculoService;

class EventoEstadoClinicoVinculoMigrationTests {
    @TempDir Path temp;

    @Test
    void migraV13PreservaOpçãoBERevisoesEReexecutaSemEscrita() throws Exception {
        var fonte = LegacyMigrationFixture.legacy(temp.resolve("v13"));
        assertEquals(13, LegacyMigrationFixture.migrarAte(fonte, "13").migrationsExecuted);
        var jdbc = new JdbcTemplate(fonte);
        var vinculo = UUID.randomUUID();
        jdbc.update("""
                insert into tb_vinculo_assistencial(id_publico,paciente_id,unidade_atuacao,unidade_chave,status_paciente,version)
                values (?,1,2,'CAPS_AD',1,0)
                """, vinculo);
        Long id = jdbc.queryForObject("select id from tb_vinculo_assistencial where id_publico=?", Long.class, vinculo);
        jdbc.update("""
                insert into tb_vinculo_assistencial_aud(id,rev,revtype,id_publico,paciente_id,unidade_atuacao,unidade_chave,status_paciente)
                values (?,2,0,?,1,2,'CAPS_AD',1)
                """, id, vinculo);
        UUID ticket = UUID.randomUUID();
        jdbc.update("""
                insert into tb_pendencia_revisao_proveniencia(id,vinculo_id,paciente_id,unidade_chave,tipo_registro,
                  registro_id,situacao_evidencia,justificativa,solicitante_id,perfil_solicitante,solicitado_em,estado)
                values (?, ?, 1,'CAPS_AD','AGENDAMENTO',4,'COMPATIVEL_SOMENTE_PARA_CONFERENCIA',
                  'Solicitação sintética para revisão da unidade',1,'ADMINISTRADOR',CURRENT_TIMESTAMP,'PENDENTE')
                """, ticket, id);
        jdbc.update("""
                insert into tb_decisao_revisao_proveniencia(id,pendencia_id,paciente_id,unidade_chave,decisao,
                  justificativa,revisor_id,perfil_revisor,decidida_em)
                values (?, ?, 1,'CAPS_AD','REJEITAR','Revisão sintética da evidência local',1,'ADMINISTRADOR',CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), ticket);

        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        CorrectiveMigrationApplication.application(fonte).run(c -> assertNotNull(c.getStartupFailure()));
        assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));
        assertEquals(1, AgendaMigrationTool.migrar(fonte).migrationsExecuted);
        colunas.remove("sibraps_agenda_schema_history");
        antes.remove("sibraps_agenda_schema_history");
        assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));
        assertEquals(0, contar(jdbc, "tb_evento_estado_clinico_vinculo"));
        assertEquals(0, contar(jdbc, "tb_evento_estado_clinico_vinculo_aud"));

        String inserir = """
                insert into tb_evento_estado_clinico_vinculo
                    (vinculo_id,unidade_chave,ator_id,ocorrido_em,natureza,status_resultante)
                values (?,?,?,CURRENT_TIMESTAMP,'INICIALIZACAO',1)
                """;
        assertThrows(DataAccessException.class, () -> jdbc.update(inserir, id, "CAPS_II", 1L));
        assertThrows(DataAccessException.class, () -> jdbc.update(inserir, id, "CAPS_AD", 999L));
        assertThrows(DataAccessException.class, () -> jdbc.update(inserir, 999L, "CAPS_AD", 1L));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                insert into tb_evento_estado_clinico_vinculo_aud(id,rev,revtype) values (999,999,0)
                """));
        var colunasV14 = LegacyMigrationFixture.columns(fonte);
        var depois = LegacyMigrationFixture.snapshot(fonte, colunasV14);
        assertEquals(0, AgendaMigrationTool.migrar(fonte).migrationsExecuted);
        assertEquals(depois, LegacyMigrationFixture.snapshot(fonte, colunasV14));

        CorrectiveMigrationApplication.application(fonte)
                .withPropertyValues("spring.profiles.active=test,diagnostico-migracao")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    var ator = ctx.getBean(UsuarioRepository.class).findById(1L).orElseThrow();
                    var paciente = ctx.getBean(PacienteRepository.class).findById(1L).orElseThrow();
                    var projecao = ctx.getBean(ProjecaoClinicaVinculoService.class);
                    assertEquals(Situacao.SEM_COMPROVACAO, como(ator,
                            () -> projecao.projetar(paciente.getIdPublico(), vinculo, LocalDate.now())).evidenciaStatus());
                    // Um evento inserido sem a revisão correspondente não comprova início.
                    assertEquals(1, jdbc.update(inserir, id, "CAPS_AD", 1L));
                    assertEquals(Situacao.CONFLITANTE, como(ator,
                            () -> projecao.projetar(paciente.getIdPublico(), vinculo, LocalDate.now())).evidenciaStatus());
                    assertThrows(DataAccessException.class, () -> jdbc.update(
                            "delete from tb_evento_estado_clinico_vinculo where vinculo_id=?", id));
                });
    }

    @Test
    void restauraV14ComEventoEnversEReverteFalhaRealDeAuditoria() throws Exception {
        var fonte = LegacyMigrationFixture.legacy(temp.resolve("nova-v14"));
        var colunasLegadas = LegacyMigrationFixture.columns(fonte);
        var dadosLegados = LegacyMigrationFixture.snapshot(fonte, colunasLegadas);
        assertEquals(14, AgendaMigrationTool.migrar(fonte).migrationsExecuted);
        assertEquals(dadosLegados, LegacyMigrationFixture.snapshot(fonte, colunasLegadas));
        var jdbc = new JdbcTemplate(fonte);
        var ids = new UUID[2];
        CorrectiveMigrationApplication.application(fonte)
                .withPropertyValues("spring.profiles.active=test,ensaio-estado-local")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    var ator = ctx.getBean(UsuarioRepository.class).findById(1L).orElseThrow();
                    var inicio = ctx.getBean(InicioAcompanhamentoLocalEnsaioService.class);
                    var tx = ctx.getBean(TransactionTemplate.class);
                    tx.executeWithoutResult(t -> {
                        var paciente = new Paciente();
                        paciente.setNome("Identidade sintética V14");
                        paciente.setCpf("93541134780");
                        paciente = ctx.getBean(PacienteRepository.class).saveAndFlush(paciente);
                        ids[0] = paciente.getIdPublico();
                        ids[1] = como(ator, () -> inicio.iniciar(ids[0], TipoAcompanhamento.INDIVIDUAL, null)).vinculo();
                    });
                    assertEquals(1, contar(jdbc, "tb_evento_estado_clinico_vinculo"));
                    assertEquals(1, contar(jdbc, "tb_evento_estado_clinico_vinculo_aud"));

                    var versao = ctx.getBean(VinculoAssistencialRepository.class)
                            .findByIdPublico(ids[1]).orElseThrow().getVersion();
                    var estado = ctx.getBean(EstadoClinicoLocalEnsaioService.class);
                    jdbc.execute("""
                            alter table tb_evento_estado_clinico_vinculo_aud
                            add constraint ck_falha_sintetica check (natureza <> 'ENCERRAMENTO')
                            """);
                    var colunas = LegacyMigrationFixture.columns(fonte);
                    var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
                    assertThrows(RuntimeException.class, () -> como(ator, () -> estado.encerrar(ids[0], ids[1],
                            versao, new EncerramentoPacienteDTO(MotivoEncerramento.ALTA_TERAPEUTICA,
                                    StatusPaciente.ALTA_TERAPEUTICA, null))));
                    assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));
                    jdbc.execute("alter table tb_evento_estado_clinico_vinculo_aud drop constraint ck_falha_sintetica");
                });

        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        var script = temp.resolve("backup-v14.sql");
        LegacyMigrationFixture.backup(fonte, script);
        var copia = LegacyMigrationFixture.restore(temp.resolve("restaurada-v14"), script);
        assertEquals(colunas, LegacyMigrationFixture.columns(copia));
        assertEquals(antes, LegacyMigrationFixture.snapshot(copia, colunas));
        var leitura = new DriverManagerDataSource(copia.getUrl() + ";ACCESS_MODE_DATA=r",
                copia.getUsername(), copia.getPassword());
        CorrectiveMigrationApplication.application(leitura)
                .withPropertyValues("spring.profiles.active=test,diagnostico-migracao")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    var ator = ctx.getBean(UsuarioRepository.class).findById(1L).orElseThrow();
                    var projecao = ctx.getBean(ProjecaoClinicaVinculoService.class);
                    assertEquals(Situacao.INICIALIZACAO_AUDITADA, como(ator,
                            () -> projecao.projetar(ids[0], ids[1], LocalDate.now())).evidenciaStatus());
                });
        assertEquals(antes, LegacyMigrationFixture.snapshot(leitura, colunas));
    }

    private int contar(JdbcTemplate jdbc, String tabela) {
        return jdbc.queryForObject("select count(*) from " + tabela, Integer.class);
    }

    private static <T> T como(Usuario ator, Supplier<T> acao) {
        var contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ator, null, ator.getAuthorities()));
        SecurityContextHolder.setContext(contexto);
        try {
            return acao.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
