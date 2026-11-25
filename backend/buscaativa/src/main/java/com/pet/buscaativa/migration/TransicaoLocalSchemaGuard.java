package com.pet.buscaativa.migration;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Valida a montagem opcional da V18 sem iniciar ou reparar migrations. */
public final class TransicaoLocalSchemaGuard {
    private TransicaoLocalSchemaGuard() {}

    public static void verificar(DataSource fonte) {
        var jdbc = new JdbcTemplate(fonte);
        try {
            var registros = jdbc.queryForList("""
                    select "success" from "sibraps_agenda_schema_history" where "version"='18'
                    """, Boolean.class);
            var tabelas = jdbc.queryForObject("""
                    select count(*) from information_schema.tables where table_schema=current_schema()
                    and table_name in ('TB_MARCO_HISTORICO_VINCULO','TB_FECHAMENTO_HISTORICO_VINCULO','TB_REVISAO_ENTREGA_RF26')
                    """, Integer.class);
            if (registros.isEmpty() && Integer.valueOf(0).equals(tabelas)) return;
            if (!registros.equals(java.util.List.of(true)) || !Integer.valueOf(3).equals(tabelas))
                throw inconsistente();

            jdbc.execute("""
                    select unidade,iniciado_em,zona,administrador,inventario
                    from tb_marco_historico_vinculo where 1=0
                    """);
            jdbc.execute("""
                    select unidade,mes,corte,certificado_em,administrador,evidencia_sha256,inventario,indicadores
                    from tb_fechamento_historico_vinculo where 1=0
                    """);
            jdbc.execute("""
                    select dia,destinatario,unidade,administrador,revisada_em,estado_anterior,justificativa
                    from tb_revisao_entrega_rf26 where 1=0
                    """);
        } catch (DataAccessException falha) {
            throw inconsistente();
        }
    }

    private static IllegalStateException inconsistente() {
        return new IllegalStateException("Banco operacional com estrutura V18 incompleta ou incompatível.");
    }
}
