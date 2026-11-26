package com.pet.buscaativa.migration;

import java.sql.SQLException;
import java.util.ArrayList;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Offline; preserva todas as solicitações, decisões, provas e aplicações existentes. */
public class V30__reabertura_origem_historica extends BaseJavaMigration {
    @Override public Integer getChecksum() {return 2026113030;}
    @Override public void migrate(Context context) throws Exception {
        var c=context.getConnection();
        var nomes=new ArrayList<String>();
        // V28 declarou UNIQUE inline; descobrir seu nome real, sem remover
        // unicidade de decisão/ticket nem a FK do histórico.
        try(var s=c.createStatement();var r=s.executeQuery("""
                select t.constraint_name from information_schema.table_constraints t
                join information_schema.key_column_usage k
                  on k.constraint_catalog=t.constraint_catalog and k.constraint_schema=t.constraint_schema
                  and k.constraint_name=t.constraint_name
                where t.table_schema=current_schema() and t.table_name='TB_APLICACAO_ORIGEM_HISTORICA'
                  and t.constraint_type='UNIQUE'
                group by t.constraint_name having count(*)=1 and min(k.column_name)='HISTORICO_ID'
                """)) {
            while(r.next()) nomes.add(r.getString(1));
        }
        if(nomes.size()!=1) throw new SQLException("Unicidade original de histórico V28 não identificada.");
        try(var s=c.createStatement()) {
            s.execute("""
                    CREATE TABLE tb_reabertura_origem_historica (
                        anterior_id UUID PRIMARY KEY,
                        novo_id UUID NOT NULL UNIQUE,
                        vinculo_id BIGINT NOT NULL,
                        paciente_id BIGINT NOT NULL,
                        unidade_chave VARCHAR(80) NOT NULL,
                        historico_id BIGINT NOT NULL,
                        tipo_registro VARCHAR(20) NOT NULL CHECK (tipo_registro='HISTORICO'),
                        situacao_evidencia VARCHAR(50) NOT NULL CHECK (situacao_evidencia='REVISAR_ORIGEM_DESCONHECIDA'),
                        motivo VARCHAR(30) NOT NULL CHECK (motivo IN ('APROVACAO_DESATUALIZADA','APLICACAO_REVERTIDA')),
                        CHECK (anterior_id<>novo_id),
                        FOREIGN KEY (anterior_id,vinculo_id,paciente_id,unidade_chave,tipo_registro,historico_id,situacao_evidencia)
                            REFERENCES tb_pendencia_revisao_proveniencia(id,vinculo_id,paciente_id,unidade_chave,tipo_registro,registro_id,situacao_evidencia),
                        FOREIGN KEY (novo_id,vinculo_id,paciente_id,unidade_chave,tipo_registro,historico_id,situacao_evidencia)
                            REFERENCES tb_pendencia_revisao_proveniencia(id,vinculo_id,paciente_id,unidade_chave,tipo_registro,registro_id,situacao_evidencia)
                    )
                    """);
            s.execute("""
                    CREATE TRIGGER tr_reabertura_origem_append_only BEFORE UPDATE, DELETE
                    ON tb_reabertura_origem_historica FOR EACH ROW CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger'
                    """);
            s.execute("ALTER TABLE tb_aplicacao_origem_historica DROP CONSTRAINT \""+nomes.getFirst().replace("\"","\"\"")+"\"");
            s.execute("CREATE INDEX ix_aplicacao_origem_historico ON tb_aplicacao_origem_historica(historico_id)");
        }
    }
}
