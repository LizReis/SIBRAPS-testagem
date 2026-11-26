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
            try(var c=fonte.getConnection()) {verificarIndices(c);}
        } catch(DataAccessException | java.sql.SQLException e) {throw inconsistente();}
    }
    private static java.util.Map<String,java.util.List<String>> indicesUnicos(java.sql.Connection c) throws java.sql.SQLException {
        var indices=new java.util.LinkedHashMap<String,java.util.List<String>>();
        try(var r=c.getMetaData().getIndexInfo(c.getCatalog(),c.getSchema(),"TB_APLICACAO_ORIGEM_HISTORICA",true,false)) {
            while(r.next()) {
                String nome=r.getString("INDEX_NAME"),coluna=r.getString("COLUMN_NAME");
                if(nome!=null && coluna!=null && !r.getBoolean("NON_UNIQUE"))
                    indices.computeIfAbsent(nome,n->new java.util.ArrayList<>()).add(coluna);
            }
        }
        return indices;
    }
    static java.util.List<String> indicesUnicosHistorico(java.sql.Connection c) throws java.sql.SQLException {
        return indicesUnicos(c).entrySet().stream()
                .filter(e->e.getValue().equals(java.util.List.of("HISTORICO_ID")))
                .map(java.util.Map.Entry::getKey).toList();
    }
    static java.util.List<String> chavesHistorico(java.sql.Connection c) throws java.sql.SQLException {
        var nomes=new java.util.ArrayList<String>();
        try(var r=c.getMetaData().getImportedKeys(c.getCatalog(),c.getSchema(),"TB_APLICACAO_ORIGEM_HISTORICA")) {
            while(r.next()) if("HISTORICO_ID".equals(r.getString("FKCOLUMN_NAME"))
                    && "TB_HISTORICO_PACIENTE".equals(r.getString("PKTABLE_NAME"))
                    && "ID".equals(r.getString("PKCOLUMN_NAME"))
                    && r.getShort("KEY_SEQ")==1) nomes.add(r.getString("FK_NAME"));
        }
        return nomes;
    }
    static void verificarIndices(java.sql.Connection c) throws java.sql.SQLException {
        var colunas=indicesUnicos(c).values();
        if(colunas.contains(java.util.List.of("HISTORICO_ID"))
                || !colunas.contains(java.util.List.of("DECISAO_ID"))
                || !colunas.contains(java.util.List.of("PENDENCIA_ID"))
                || chavesHistorico(c).size()!=1)
            throw new java.sql.SQLException("V30: índices de aplicação ou FK de histórico incompatíveis.");
    }
    private static IllegalStateException inconsistente() {
        return new IllegalStateException("Banco operacional com estrutura V30 de reabertura de origem incompleta ou incompatível.");
    }
}
