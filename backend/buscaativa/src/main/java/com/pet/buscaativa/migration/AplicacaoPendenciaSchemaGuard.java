package com.pet.buscaativa.migration;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;

public final class AplicacaoPendenciaSchemaGuard {
    private AplicacaoPendenciaSchemaGuard() {}
    private static boolean objeto(JdbcTemplate jdbc,String nome) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from information_schema.tables where table_schema=current_schema() and table_name=?)
                """,Boolean.class,nome));
    }
    public static boolean instalada(JdbcTemplate jdbc) {
        return objeto(jdbc,"TB_APLICACAO_PENDENCIA_RESOLUCAO") && objeto(jdbc,"VW_APLICACAO_PENDENCIA_ATIVA");
    }
    public static void verificar(DataSource fonte) {
        var jdbc=new JdbcTemplate(fonte);
        try {
            var registros=jdbc.queryForList("""
                    select "success" from "sibraps_agenda_schema_history" where "version"='25'
                    """,Boolean.class);
            boolean tabela=objeto(jdbc,"TB_APLICACAO_PENDENCIA_RESOLUCAO");
            boolean visao=objeto(jdbc,"VW_APLICACAO_PENDENCIA_ATIVA");
            int triggers=jdbc.queryForObject("""
                    select count(*) from information_schema.triggers where event_object_schema=current_schema()
                    and trigger_name='TR_APLICACAO_PENDENCIA_APPEND_ONLY'
                    """,Integer.class);
            if(registros.isEmpty() && !tabela && !visao && triggers==0) return;
            int protecao=jdbc.queryForObject("""
                    select count(*) from information_schema.triggers where event_object_schema=current_schema()
                    and trigger_name='TR_APLICACAO_PENDENCIA_APPEND_ONLY'
                    and event_object_table='TB_APLICACAO_PENDENCIA_RESOLUCAO' and action_timing='BEFORE'
                    and event_manipulation in ('UPDATE','DELETE')
                    and java_class='com.pet.buscaativa.migration.AuditAppendOnlyTrigger'
                    """,Integer.class);
            if(!registros.equals(java.util.List.of(true)) || !tabela || !visao || triggers!=2 || protecao!=2
                    || !ReversaoResolucaoSchemaGuard.instalada(jdbc)) throw inconsistente();
            for(String nome:java.util.List.of("tb_aplicacao_pendencia_resolucao","vw_aplicacao_pendencia_ativa"))
                jdbc.execute("select resolucao_id,pendencia_id,decisao_id,vinculo_id,paciente_id,unidade_chave,decisao from "+nome+" where 1=0");
        } catch(DataAccessException e) {throw inconsistente();}
    }
    private static IllegalStateException inconsistente() {
        return new IllegalStateException("Banco operacional com estrutura V25 de aplicação de pendência incompleta ou incompatível.");
    }
}
