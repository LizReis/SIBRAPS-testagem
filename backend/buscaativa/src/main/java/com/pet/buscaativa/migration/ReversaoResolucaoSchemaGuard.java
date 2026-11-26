package com.pet.buscaativa.migration;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;

public final class ReversaoResolucaoSchemaGuard {
    private ReversaoResolucaoSchemaGuard() {}

    private static boolean objeto(JdbcTemplate jdbc,String nome) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from information_schema.tables
                where table_schema=current_schema() and table_name=?)
                """,Boolean.class,nome));
    }

    public static boolean instalada(JdbcTemplate jdbc) {
        return objeto(jdbc,"TB_REVERSAO_RESOLUCAO_FREQUENCIA")
                && objeto(jdbc,"VW_RESOLUCAO_FREQUENCIA_ATIVA");
    }

    public static void verificar(DataSource fonte) {
        var jdbc=new JdbcTemplate(fonte);
        try {
            var registros=jdbc.queryForList("""
                    select "success" from "sibraps_agenda_schema_history" where "version"='24'
                    """,Boolean.class);
            boolean tabela=objeto(jdbc,"TB_REVERSAO_RESOLUCAO_FREQUENCIA");
            boolean visao=objeto(jdbc,"VW_RESOLUCAO_FREQUENCIA_ATIVA");
            int triggers=jdbc.queryForObject("""
                    select count(*) from information_schema.triggers where event_object_schema=current_schema()
                    and trigger_name='TR_REVERSAO_RESOLUCAO_APPEND_ONLY'
                    """,Integer.class);
            if(registros.isEmpty() && !tabela && !visao && triggers==0) return;
            int protecao=jdbc.queryForObject("""
                    select count(*) from information_schema.triggers where event_object_schema=current_schema()
                    and trigger_name='TR_REVERSAO_RESOLUCAO_APPEND_ONLY'
                    and event_object_table='TB_REVERSAO_RESOLUCAO_FREQUENCIA' and action_timing='BEFORE'
                    and event_manipulation in ('UPDATE','DELETE')
                    and java_class='com.pet.buscaativa.migration.AuditAppendOnlyTrigger'
                    """,Integer.class);
            if(!registros.equals(java.util.List.of(true)) || !tabela || !visao || triggers!=2 || protecao!=2
                    || !ResolucaoFrequenciaSchemaGuard.instalada(jdbc)) throw inconsistente();
            jdbc.execute("select id,resolucao_id,vinculo_id,paciente_id,unidade_chave,responsavel_valido,responsavel_desconsiderado,ator_id,perfil,justificativa,registrado_em from tb_reversao_resolucao_frequencia where 1=0");
            jdbc.execute("select id,vinculo_id,fonte_valida,fato_valido,fonte_desconsiderada,fato_desconsiderado from vw_resolucao_frequencia_ativa where 1=0");
        } catch(DataAccessException e) {throw inconsistente();}
    }
    private static IllegalStateException inconsistente() {
        return new IllegalStateException("Banco operacional com estrutura V24 de reversão incompleta ou incompatível.");
    }
}
