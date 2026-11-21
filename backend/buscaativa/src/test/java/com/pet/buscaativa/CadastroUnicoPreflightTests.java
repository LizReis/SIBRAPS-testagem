package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import com.pet.buscaativa.migration.AgendaMigrationTool;
import com.pet.buscaativa.migration.CadastroUnicoPreflight;

/**
 * Preflight diagnostico nao cria vinculos nem altera dados clinicos.
 * Nao divulga CPF/CNS, somente quantidades.
 */
class CadastroUnicoPreflightTests {
    @TempDir Path pasta;

    @Test
    void relatorioReadOnlyNaoModificaFixtureNemInventaVinculos() throws Exception {
        var fonte = LegacyMigrationFixture.legacy(pasta.resolve("base"));
        AgendaMigrationTool.migrar(fonte);

        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        var resultado = CadastroUnicoPreflight.analisar(fonte);

        assertEquals(2, resultado.totalPacientes());
        assertEquals(0, resultado.vinculosAssistenciaisRegistrados());
        assertEquals(0, resultado.documentosCpfDuplicados());
        assertEquals(0, resultado.documentosCnsDuplicados());
        assertEquals(2, resultado.pacientesSemCpf());
        assertEquals(2, resultado.pacientesSemCns());
        assertTrue(resultado.exigeRevisao());
        assertEquals(0, resultado.agendamentosComUsuarioSemUnidadeConfigurada());
        assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));
        assertEquals(0, AgendaMigrationTool.migrar(fonte).migrationsExecuted);
    }

    @Test
    void documentosRepetidosSaoApontadosSemExporIdentificadores() throws Exception {
        var fonte = LegacyMigrationFixture.legacy(pasta.resolve("repetidos"));
        AgendaMigrationTool.migrar(fonte);

        var jdbc = new JdbcTemplate(fonte);
        // A fixture antiga guarda CPF/CNS nulos. Criar aqui somente
        // documentos sintéticos explícitos para reproduzir a duplicação.
        // Formatos diferentes devem representar a mesma identidade.
        String cpf = "52998224725";
        String cns = "700000000000005";
        jdbc.update("update tb_paciente set cpf=?, cns=? where id=1", cpf, cns);
        jdbc.update("update tb_paciente set cpf=?, cns=? where id=2", "529.982.247-25", " " + cns + " ");

        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        var resultado = CadastroUnicoPreflight.analisar(fonte);

        assertEquals(1, resultado.documentosCpfDuplicados());
        assertEquals(1, resultado.documentosCnsDuplicados());
        assertTrue(resultado.exigeRevisao());
        assertFalse(resultado.toString().contains(cpf));
        assertFalse(resultado.toString().contains(cns));
        assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));
    }

    @Test
    void faltaDeOrigemHistoricaRequerRevisaoSemAtribuirUnidade() throws Exception {
        var fonte = LegacyMigrationFixture.legacy(pasta.resolve("historico"));
        AgendaMigrationTool.migrar(fonte);
        var jdbc = new JdbcTemplate(fonte);
        jdbc.update("update tb_historico_paciente set agendamento_id=null, sessao_grupo_id=null where id=1");

        var antes = LegacyMigrationFixture.snapshot(fonte, LegacyMigrationFixture.columns(fonte));
        var resultado = CadastroUnicoPreflight.analisar(fonte);

        assertTrue(resultado.historicosSemOrigemDeAtendimentoOuGrupo() > 0);
        assertTrue(resultado.exigeRevisao());
        assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, LegacyMigrationFixture.columns(fonte)));
    }
}
