package com.pet.buscaativa.migration;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;

public final class ReaberturaOrigemSchemaGuard {
    private ReaberturaOrigemSchemaGuard() {}
    public static boolean instalada(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from information_schema.tables where table_schema=current_schema()
                and table_name='TB_REABERTURA_ORIGEM_HISTORICA')
                """,Boolean.class));
    }
    public static void verificar(DataSource fonte) {
        var jdbc=new JdbcTemplate(fonte);
        try {
            var registros=jdbc.queryForList("""
                    select "success" from "sibraps_agenda_schema_history" where "version"='30'
                    """,Boolean.class);
            boolean tabela=instalada(jdbc);
            int triggers=jdbc.queryForObject("""
                    select count(*) from information_schema.triggers where event_object_schema=current_schema()
                    and trigger_name='TR_REABERTURA_ORIGEM_APPEND_ONLY'
                    """,Integer.class);
            if(registros.isEmpty() && !tabela && triggers==0) return;
            int protecao=jdbc.queryForObject("""
                    select count(*) from information_schema.triggers where event_object_schema=current_schema()
                    and trigger_name='TR_REABERTURA_ORIGEM_APPEND_ONLY'
                    and event_object_table='TB_REABERTURA_ORIGEM_HISTORICA' and action_timing='BEFORE'
                    and event_manipulation in ('UPDATE','DELETE')
                    and java_class='com.pet.buscaativa.migration.AuditAppendOnlyTrigger'
                    """,Integer.class);
            if(!registros.equals(java.util.List.of(true)) || !tabela || triggers!=2 || protecao!=2
                    || !ReversaoOrigemSchemaGuard.instalada(jdbc)) throw inconsistente();
            jdbc.execute("select anterior_id,novo_id,vinculo_id,paciente_id,unidade_chave,historico_id,tipo_registro,situacao_evidencia,motivo from tb_reabertura_origem_historica where 1=0");
        } catch(DataAccessException e) {throw inconsistente();}
    }
    private static IllegalStateException inconsistente() {
        return new IllegalStateException("Banco operacional com estrutura V30 de reabertura de origem incompleta ou incompatível.");
    }
}
