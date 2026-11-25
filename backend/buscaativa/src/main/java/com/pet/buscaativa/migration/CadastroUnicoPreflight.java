package com.pet.buscaativa.migration;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Diagnóstico SOMENTE LEITURA de um banco já versionado até a V8.
 *
 * Não cria pacientes/vínculos, não atribui unidade de origem a fatos clínicos,
 * não retorna documentos pessoais e não executa migração automaticamente.
 * Os resultados são CONTAGENS de risco para revisão prévia à separação por unidade.
 */
public final class CadastroUnicoPreflight {
    private CadastroUnicoPreflight() {}

    public record Relatorio(
            long totalPacientes,
            long documentosCpfDuplicados,
            long documentosCnsDuplicados,
            long pacientesSemCpf,
            long pacientesSemCns,
            long referenciasCapsSemVinculoClinico,
            long agendamentosComUsuarioSemUnidadeConfigurada,
            long historicosSemOrigemDeAtendimentoOuGrupo,
            long vinculosAssistenciaisRegistrados
    ) {
        /** Indícios que exigem revisão; históricos manuais legítimos também podem não ter fonte vinculada. */
        public boolean exigeRevisao() {
            return documentosCpfDuplicados > 0 || documentosCnsDuplicados > 0
                    || pacientesSemCpf > 0 || pacientesSemCns > 0
                    || agendamentosComUsuarioSemUnidadeConfigurada > 0
                    || historicosSemOrigemDeAtendimentoOuGrupo > 0;
        }
    }

    public static Relatorio analisar(DataSource fonte) {
        if (fonte == null) throw new IllegalArgumentException("Fonte de dados obrigatória.");
        JdbcTemplate jdbc = new JdbcTemplate(fonte);
        // Toda consulta é SELECT; não assumir que uma referência de CAPS equivale a acompanhamento.
        long pacientes = contar(jdbc, "select count(*) from tb_paciente");
        long cpfs = contar(jdbc, """
            select count(*) from (
                select nullif(regexp_replace(cpf, '[^0-9]', ''), '') doc
                from tb_paciente where nullif(regexp_replace(cpf, '[^0-9]', ''), '') is not null
                group by nullif(regexp_replace(cpf, '[^0-9]', ''), '')
                having count(*) > 1
            ) repetidos
            """);
        long cns = contar(jdbc, """
            select count(*) from (
                select nullif(regexp_replace(cns, '[^0-9]', ''), '') doc
                from tb_paciente where nullif(regexp_replace(cns, '[^0-9]', ''), '') is not null
                group by nullif(regexp_replace(cns, '[^0-9]', ''), '')
                having count(*) > 1
            ) repetidos
            """);
        long semCpf = contar(jdbc, "select count(*) from tb_paciente where cpf is null or trim(cpf) = ''");
        long semCns = contar(jdbc, "select count(*) from tb_paciente where cns is null or trim(cns) = ''");
        // CapsEnum tem códigos 1..3, UnidadeAtuacao CAPS 2..4; a referência não concede vínculo.
        long referenciasSemVinculo = contar(jdbc, """
            select count(*) from tb_paciente p
            where p.caps_referencia is not null
              and not exists (
                  select 1 from tb_vinculo_assistencial v
                  where v.paciente_id = p.id
                    and v.unidade_atuacao = p.caps_referencia + 1
              )
            """);
        long usuariosSemUnidade = contar(jdbc, """
            select count(*) from tb_agendamento a
            left join tb_usuario u on u.id = a.usuario_id
            where u.id is null or u.unidade_atuacao is null
               or not ((u.unidade_atuacao in (2, 3, 4) and u.usf_atuacao_id is null)
                    or (u.unidade_atuacao = 1 and u.usf_atuacao_id is not null))
            """);
        long historicosSemFonte = contar(jdbc, """
            select count(*) from tb_historico_paciente
            where agendamento_id is null and sessao_grupo_id is null
            """);
        long vinculos = contar(jdbc, "select count(*) from tb_vinculo_assistencial");
        return new Relatorio(pacientes, cpfs, cns, semCpf, semCns, referenciasSemVinculo,
                usuariosSemUnidade, historicosSemFonte, vinculos);
    }

    private static long contar(JdbcTemplate jdbc, String sql) {
        Long total = jdbc.queryForObject(sql, Long.class);
        return total == null ? 0 : total;
    }
}
