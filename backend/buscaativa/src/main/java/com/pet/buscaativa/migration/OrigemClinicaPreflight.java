package com.pet.buscaativa.migration;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Triagem read-only de proveniencia dos registros clinicos antigos.
 *
 * IMPORTANTE: a unidade atual de um usuario e APENAS INDICIO; nao prova a
 * unidade historica de um atendimento. Este diagnostico nunca atribui
 * vinculos assistenciais e nunca produz DTOs identificaveis.
 *
 * Requer schema explicitamente migrado ate V8. Sem endpoint HTTP.
 * ATENCAO: todas as unidades observadas neste preflight sao INDICIOS;
 * mesmo 100% de profissionais com unidade configurada nao prova origem
 * historica nem autoriza marcar a migracao como pronta.
 */
public final class OrigemClinicaPreflight {
    private OrigemClinicaPreflight() {}

    public record Relatorio(
            long agendamentosTotais,
            long agendamentosComIndicioNaUnidadeAtualDoUsuario,
            long agendamentosSemIndicioDaUnidade,
            long historicosTotais,
            long historicosAssociadosAoAgendamentoDoMesmoPaciente,
            long historicosAssociadosASessaoRealizadaComResponsavelRegistrado,
            long historicosSemFonteDireta,
            long historicosComFonteDePacienteInconsistente,
            long sessoesRealizadasSemResponsavelConfirmado
    ) {
        /**
         * Este preflight opera sobre o esquema V8 e NAO consulta prova
         * historica de unidade. Mesmo quando ha indicio pela lotacao atual
         * e a fonte pertence ao mesmo paciente, nao ha garantia de origem.
         * Por seguranca, qualquer fato legado exige revisao/prova externa
         * antes do corte; lista vazia pode ser considerada sem risco por
         * ausencia de fatos a atribuir.
         */
        public boolean necessitaRevisaoHistorica() {
            return agendamentosTotais > 0 || historicosTotais > 0
                    || agendamentosSemIndicioDaUnidade > 0
                    || historicosSemFonteDireta > 0
                    || historicosComFonteDePacienteInconsistente > 0
                    || sessoesRealizadasSemResponsavelConfirmado > 0;
        }
    }

    /** Somente SELECTs. Os campos retornados sao exclusivamente contagens. */
    public static Relatorio analisar(DataSource source) {
        if (source == null) throw new IllegalArgumentException("Fonte de dados obrigatoria.");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        long agendamentos = contar(jdbc, "select count(*) from tb_agendamento");
        long agendamentosComIndicio = contar(jdbc, """
                select count(*) from tb_agendamento a
                join tb_usuario u on u.id = a.usuario_id
                where u.unidade_atuacao in (2,3,4)
                   or (u.unidade_atuacao = 1 and u.usf_atuacao_id is not null)
                """);
        long historicos = contar(jdbc, "select count(*) from tb_historico_paciente");
        long historiasAgendamento = contar(jdbc, """
                select count(*) from tb_historico_paciente h
                where h.agendamento_id is not null
                  and exists (
                      select 1 from tb_agendamento a
                      where a.id = h.agendamento_id and a.paciente_id = h.paciente_id
                  )
                """);
        // Responsavel clinico confirmado durante a realizacao, nunca coordenador
        // atual do grupo. A inscricao deve pertencer ao mesmo paciente e sessao.
        long historiasGrupo = contar(jdbc, """
                select count(*) from tb_historico_paciente h
                where h.sessao_grupo_id is not null
                  and exists (
                      select 1 from tb_sessao_grupo s
                      join tb_usuario u on u.id = s.responsavel_atendimento_id
                      where s.id = h.sessao_grupo_id
                        and s.status = 'REALIZADA'
                        and s.responsabilidade_pendente = false
                        and (u.unidade_atuacao in (2,3,4)
                             or (u.unidade_atuacao = 1 and u.usf_atuacao_id is not null))
                        and exists (
                            select 1 from tb_sessao_grupo_participante p
                            where p.sessao_grupo_id = s.id and p.paciente_id = h.paciente_id
                        )
                  )
                """);
        long semFonte = contar(jdbc, """
                select count(*) from tb_historico_paciente h
                where h.agendamento_id is null and h.sessao_grupo_id is null
                """);
        long inconsistentes = contar(jdbc, """
                select count(*) from tb_historico_paciente h
                where (h.agendamento_id is not null
                       and not exists (
                           select 1 from tb_agendamento a
                           where a.id = h.agendamento_id and a.paciente_id = h.paciente_id
                       ))
                   or (h.sessao_grupo_id is not null
                       and not exists (
                           select 1 from tb_sessao_grupo_participante p
                           where p.sessao_grupo_id = h.sessao_grupo_id
                             and p.paciente_id = h.paciente_id
                       ))
                """);
        long sessoesSemAutor = contar(jdbc, """
                select count(*) from tb_sessao_grupo s
                where s.status = 'REALIZADA'
                  and (s.responsavel_atendimento_id is null
                       or s.responsabilidade_pendente = true)
                """);
        return new Relatorio(
                agendamentos, agendamentosComIndicio, agendamentos - agendamentosComIndicio,
                historicos, historiasAgendamento, historiasGrupo, semFonte,
                inconsistentes, sessoesSemAutor);
    }

    private static long contar(JdbcTemplate jdbc, String sql) {
        Long result = jdbc.queryForObject(sql, Long.class);
        return result == null ? 0L : result;
    }
}
