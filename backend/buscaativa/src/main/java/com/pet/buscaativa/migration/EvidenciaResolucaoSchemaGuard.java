package com.pet.buscaativa.migration;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;

public final class EvidenciaResolucaoSchemaGuard {
    private EvidenciaResolucaoSchemaGuard() {}
    public static boolean instalada(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from information_schema.tables where table_schema=current_schema()
                and table_name='TB_EVIDENCIA_RESOLUCAO_HISTORICA')
                """,Boolean.class));
    }
    public static void verificar(DataSource fonte) {
        var jdbc=new JdbcTemplate(fonte);
        try {
            var registros=jdbc.queryForList("""
                    select "success" from "sibraps_agenda_schema_history" where "version"='26'
                    """,Boolean.class);
            boolean tabela=instalada(jdbc);
            int triggers=jdbc.queryForObject("""
                    select count(*) from information_schema.triggers where event_object_schema=current_schema()
                    and trigger_name='TR_EVIDENCIA_RESOLUCAO_APPEND_ONLY'
                    """,Integer.class);
            if(registros.isEmpty() && !tabela && triggers==0) return;
            int protecao=jdbc.queryForObject("""
                    select count(*) from information_schema.triggers where event_object_schema=current_schema()
                    and trigger_name='TR_EVIDENCIA_RESOLUCAO_APPEND_ONLY'
                    and event_object_table='TB_EVIDENCIA_RESOLUCAO_HISTORICA' and action_timing='BEFORE'
                    and event_manipulation in ('UPDATE','DELETE')
                    and java_class='com.pet.buscaativa.migration.AuditAppendOnlyTrigger'
                    """,Integer.class);
            if(!registros.equals(java.util.List.of(true)) || !tabela || triggers!=2 || protecao!=2
                    || !AplicacaoPendenciaSchemaGuard.instalada(jdbc)) throw inconsistente();
            jdbc.execute("select tipo,evento_id,unidade_chave,assinatura from tb_evidencia_resolucao_historica where 1=0");
        } catch(DataAccessException e) {throw inconsistente();}
    }
    private static IllegalStateException inconsistente() {
        return new IllegalStateException("Banco operacional com estrutura V26 de evidência de resolução incompleta ou incompatível.");
    }
}
