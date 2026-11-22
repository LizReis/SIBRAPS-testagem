package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import com.pet.buscaativa.migration.AgendaMigrationTool;
import com.pet.buscaativa.repositories.PendenciaRevisaoProvenienciaRepository;

/**
 * Contratos defensivos para a consulta interna V12, usando apenas fixture
 * sintetica e H2 em arquivo. Nao habilita API, escrita pela aplicacao,
 * conciliacao ou visibilidade entre unidades.
 */
class PendenciaProvenienciaConsultasEscopadasTests {
    @TempDir Path temp;

    @Test
    void consultaPorPacienteEUnidadeNaoMisturaPendenciasDoMesmoPaciente() throws Exception {
        var source = LegacyMigrationFixture.legacy(temp.resolve("escopo-pendencias-v12"));
        AgendaMigrationTool.migrar(source);
        var jdbc = new JdbcTemplate(source);

        // Uma identidade cadastral acompanhada por duas unidades independentes.
        jdbc.update("""
                insert into tb_vinculo_assistencial(
                    id_publico, paciente_id, unidade_atuacao, unidade_chave,
                    status_paciente, version)
                values (?, 1, 2, 'CAPS_AD', 1, 0)
                """, UUID.randomUUID());
        jdbc.update("""
                insert into tb_vinculo_assistencial(
                    id_publico, paciente_id, unidade_atuacao, unidade_chave,
                    status_paciente, version)
                values (?, 1, 4, 'CAPS_II', 1, 0)
                """, UUID.randomUUID());

        Long vinculoAd = jdbc.queryForObject(
                "select id from tb_vinculo_assistencial where paciente_id=1 and unidade_chave='CAPS_AD'",
                Long.class);
        Long vinculoIi = jdbc.queryForObject(
                "select id from tb_vinculo_assistencial where paciente_id=1 and unidade_chave='CAPS_II'",
                Long.class);

        String insert = """
                insert into tb_pendencia_revisao_proveniencia(
                    id, vinculo_id, paciente_id, unidade_chave, tipo_registro,
                    registro_id, situacao_evidencia, justificativa, solicitante_id,
                    perfil_solicitante, solicitado_em, estado)
                values (?, ?, 1, ?, 'AGENDAMENTO', ?,
                    'REVISAR_ORIGEM_DESCONHECIDA',
                    'Solicitacao sintetica para revisao de proveniencia',
                    1, 'ADMINISTRADOR', CURRENT_TIMESTAMP, 'PENDENTE')
                """;
        UUID ticketAd = UUID.randomUUID();
        UUID ticketIi = UUID.randomUUID();
        jdbc.update(insert, ticketAd, vinculoAd, "CAPS_AD", 4L);
        jdbc.update(insert, ticketIi, vinculoIi, "CAPS_II", 5L);

        // A consulta interna deve combinar paciente E unidade, jamais apenas CPF/CNS
        // (ou somente o id publico do ticket) como criterio de autorizacao.
        String consulta = """
                select id from tb_pendencia_revisao_proveniencia
                where paciente_id=? and unidade_chave=? and estado='PENDENTE'
                order by solicitado_em desc
                """;
        assertEquals(List.of(ticketAd), jdbc.queryForList(consulta, UUID.class, 1L, "CAPS_AD"));
        assertEquals(List.of(ticketIi), jdbc.queryForList(consulta, UUID.class, 1L, "CAPS_II"));
        assertEquals(List.of(), jdbc.queryForList(consulta, UUID.class, 1L, "CAPS_I"));
        assertEquals(List.of(), jdbc.queryForList(consulta, UUID.class, 2L, "CAPS_AD"));

        String porId = """
                select count(*) from tb_pendencia_revisao_proveniencia
                where id=? and paciente_id=? and unidade_chave=?
                """;
        assertEquals(0, jdbc.queryForObject(porId, Integer.class, ticketIi, 1L, "CAPS_AD"));
        assertEquals(0, jdbc.queryForObject(porId, Integer.class, ticketAd, 2L, "CAPS_AD"));
        assertEquals(1, jdbc.queryForObject(porId, Integer.class, ticketAd, 1L, "CAPS_AD"));
    }

    @Test
    void repositorioNaoExpoeOperacoesDeEscritaOuBuscaGlobal() {
        var metodos = Arrays.stream(PendenciaRevisaoProvenienciaRepository.class.getDeclaredMethods())
                .map(m -> m.getName())
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "findByIdAndPacienteIdAndUnidadeChave",
                "findByPacienteIdAndUnidadeChaveAndEstadoOrderBySolicitadoEmDesc"), metodos);
        assertFalse(metodos.contains("save"));
        assertFalse(metodos.contains("findAll"));
        assertFalse(metodos.contains("deleteById"));
    }
}
