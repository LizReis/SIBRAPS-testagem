package com.pet.buscaativa.migration;

import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V19 é opcional até o corte; montagem parcial ou sem triggers é recusada. */
public final class InventarioHistoricoSchemaGuard {
    private InventarioHistoricoSchemaGuard() {}

    public static void verificar(DataSource fonte) {
        var jdbc = new JdbcTemplate(fonte);
        try {
            var registros = jdbc.queryForList("""
                    select "success" from "sibraps_agenda_schema_history" where "version"='19'
                    """, Boolean.class);
            int tabelas = jdbc.queryForObject("""
                    select count(*) from information_schema.tables where table_schema=current_schema()
                    and table_name='TB_MOVIMENTO_INVENTARIO_HISTORICO'
                    """, Integer.class);
            int triggers = jdbc.queryForObject("""
                    select count(*) from information_schema.triggers where event_object_schema=current_schema()
                    and trigger_name in ('TR_INVENTARIO_HISTORICO_MOVIMENTO','TR_INVENTARIO_HISTORICO_APPEND_ONLY')
                    """, Integer.class);
            if (registros.isEmpty() && tabelas == 0 && triggers == 0) return;
            if (!registros.equals(java.util.List.of(true)) || tabelas != 1 || triggers != 5)
                throw inconsistente();
            jdbc.execute("""
                    select id,registrado_em,operacao,vinculo_anterior,paciente_anterior,unidade_anterior,
                    vinculo_atual,paciente_atual,unidade_atual from tb_movimento_inventario_historico where 1=0
                    """);
            int captura = jdbc.queryForObject("""
                    select count(*) from information_schema.triggers where event_object_schema=current_schema()
                    and trigger_name='TR_INVENTARIO_HISTORICO_MOVIMENTO' and event_object_table='TB_VINCULO_ASSISTENCIAL'
                    and action_timing='AFTER' and event_manipulation in ('INSERT','UPDATE','DELETE')
                    and java_class='com.pet.buscaativa.migration.InventarioHistoricoTrigger'
                    """, Integer.class);
            int protecao = jdbc.queryForObject("""
                    select count(*) from information_schema.triggers where event_object_schema=current_schema()
                    and trigger_name='TR_INVENTARIO_HISTORICO_APPEND_ONLY' and event_object_table='TB_MOVIMENTO_INVENTARIO_HISTORICO'
                    and action_timing='BEFORE' and event_manipulation in ('UPDATE','DELETE')
                    and java_class='com.pet.buscaativa.migration.AuditAppendOnlyTrigger'
                    """, Integer.class);
            if (captura != 3 || protecao != 2) throw inconsistente();
        } catch (DataAccessException falha) {
            throw inconsistente();
        }
    }

    private static IllegalStateException inconsistente() {
        return new IllegalStateException("Banco operacional com estrutura V19 de inventário incompleta ou incompatível.");
    }
}
