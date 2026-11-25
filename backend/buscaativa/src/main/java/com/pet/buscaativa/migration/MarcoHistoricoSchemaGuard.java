package com.pet.buscaativa.migration;

import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Não instala, repara ou ativa a coleta no startup. */
public final class MarcoHistoricoSchemaGuard {
    private MarcoHistoricoSchemaGuard() {}

    public static void verificar(DataSource fonte) {
        var jdbc = new JdbcTemplate(fonte);
        try {
            var registros = jdbc.queryForList("""
                    select "success" from "sibraps_agenda_schema_history" where "version"='20'
                    """, Boolean.class);
            int tabelas = jdbc.queryForObject("""
                    select count(*) from information_schema.tables where table_schema=current_schema()
                    and table_name='TB_MARCO_TECNICO_HISTORICO'
                    """, Integer.class);
            int triggers = jdbc.queryForObject("""
                    select count(*) from information_schema.triggers where event_object_schema=current_schema()
                    and trigger_name='TR_MARCO_TECNICO_HISTORICO_APPEND_ONLY'
                    """, Integer.class);
            if (registros.isEmpty() && tabelas == 0 && triggers == 0) return;
            int protecao = jdbc.queryForObject("""
                    select count(*) from information_schema.triggers where event_object_schema=current_schema()
                    and trigger_name='TR_MARCO_TECNICO_HISTORICO_APPEND_ONLY'
                    and event_object_table='TB_MARCO_TECNICO_HISTORICO'
                    and action_timing='BEFORE' and event_manipulation in ('UPDATE','DELETE')
                    and java_class='com.pet.buscaativa.migration.AuditAppendOnlyTrigger'
                    """, Integer.class);
            if (!registros.equals(java.util.List.of(true)) || tabelas != 1 || triggers != 2 || protecao != 2)
                throw inconsistente();
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from "sibraps_agenda_schema_history" where "version"='19' and "success"=true)
                    """, Boolean.class))) throw inconsistente();
            jdbc.execute("""
                    select id,iniciado_em,zona,ultimo_movimento,total_unidades,total_vinculos,inventario
                    from tb_marco_tecnico_historico where 1=0
                    """);
        } catch (DataAccessException falha) {
            throw inconsistente();
        }
    }

    private static IllegalStateException inconsistente() {
        return new IllegalStateException("Banco operacional com estrutura V20 de marco histórico incompleta ou incompatível.");
    }
}
