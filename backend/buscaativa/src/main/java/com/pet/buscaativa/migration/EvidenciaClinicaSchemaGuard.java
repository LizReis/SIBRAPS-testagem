package com.pet.buscaativa.migration;

import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class EvidenciaClinicaSchemaGuard {
    private EvidenciaClinicaSchemaGuard() {}
    private static final String[] TABELAS = {"TB_MOVIMENTO_EVIDENCIA_CLINICA", "TB_BASE_EVIDENCIA_CLINICA", "TB_ITEM_BASE_EVIDENCIA_CLINICA"};
    private static final String[] FONTES = {"TB_VINCULO_ASSISTENCIAL", "TB_PACIENTE", "TB_AGENDAMENTO", "TB_GRUPO_TERAPEUTICO", "TB_SESSAO_GRUPO", "TB_SESSAO_GRUPO_PARTICIPANTE", "TB_EVENTO_ESTADO_CLINICO_VINCULO"};

    static boolean instalada(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from "sibraps_agenda_schema_history" where "version"='21' and "success"=true)
                """, Boolean.class));
    }

    public static void verificar(DataSource fonte) {
        var jdbc = new JdbcTemplate(fonte);
        try {
            var registros = jdbc.queryForList("""
                    select "success" from "sibraps_agenda_schema_history" where "version"='21'
                    """, Boolean.class);
            int tabelas = jdbc.queryForObject("""
                    select count(*) from information_schema.tables where table_schema=current_schema()
                    and table_name in ('TB_MOVIMENTO_EVIDENCIA_CLINICA','TB_BASE_EVIDENCIA_CLINICA','TB_ITEM_BASE_EVIDENCIA_CLINICA')
                    """, Integer.class);
            int triggers = jdbc.queryForObject("""
                    select count(*) from information_schema.triggers where event_object_schema=current_schema()
                    and (trigger_name like 'TR_EVIDENCIA_CLINICA_F%' or trigger_name like 'TR_EVIDENCIA_CLINICA_PROTECAO%')
                    """, Integer.class);
            if (registros.isEmpty() && tabelas == 0 && triggers == 0) return;
            if (!registros.equals(java.util.List.of(true)) || tabelas != 3 || triggers != 27)
                throw inconsistente();
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from "sibraps_agenda_schema_history" where "version"='20' and "success"=true)
                    """, Boolean.class))) throw inconsistente();
            jdbc.execute("select id,registrado_em,fonte,operacao,registro_anterior,registro_atual,assinatura_anterior,assinatura_atual from tb_movimento_evidencia_clinica where 1=0");
            jdbc.execute("select marco_id,ultimo_movimento,total_registros from tb_base_evidencia_clinica where 1=0");
            jdbc.execute("select marco_id,fonte,registro,assinatura from tb_item_base_evidencia_clinica where 1=0");
            for (int i=0; i<FONTES.length; i++) {
                int n=jdbc.queryForObject("""
                        select count(*) from information_schema.triggers where event_object_schema=current_schema()
                        and trigger_name=? and event_object_table=? and action_timing='AFTER'
                        and event_manipulation in ('INSERT','UPDATE','DELETE')
                        and java_class='com.pet.buscaativa.migration.EvidenciaClinicaTrigger'
                        """,Integer.class,"TR_EVIDENCIA_CLINICA_F"+(i+1),FONTES[i]);
                if(n!=3) throw inconsistente();
                jdbc.execute("select "+String.join(",",AssinaturaEvidenciaClinica.FONTES.get(FONTES[i]))+" from "+FONTES[i]+" where 1=0");
            }
            for (int i=0; i<TABELAS.length; i++) {
                int n=jdbc.queryForObject("""
                        select count(*) from information_schema.triggers where event_object_schema=current_schema()
                        and trigger_name=? and event_object_table=? and action_timing='BEFORE'
                        and event_manipulation in ('UPDATE','DELETE')
                        and java_class='com.pet.buscaativa.migration.AuditAppendOnlyTrigger'
                        """,Integer.class,"TR_EVIDENCIA_CLINICA_PROTECAO"+(i+1),TABELAS[i]);
                if(n!=2) throw inconsistente();
            }
        } catch (DataAccessException falha) {
            throw inconsistente();
        }
    }
    private static IllegalStateException inconsistente() {
        return new IllegalStateException("Banco operacional com estrutura V21 de evidência clínica incompleta ou incompatível.");
    }
}
