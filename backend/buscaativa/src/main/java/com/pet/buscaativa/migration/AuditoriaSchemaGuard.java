package com.pet.buscaativa.migration;

import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component @RequiredArgsConstructor
public class AuditoriaSchemaGuard implements SmartInitializingSingleton {
    private final DataSource source;
    private final Environment environment;
    @Override public void afterSingletonsInstantiated() {
        try {
          try(var connection=source.getConnection()) {
            var ddl=environment.getProperty("spring.jpa.hibernate.ddl-auto","");
            // Only explicitly recreated in-memory databases receive development/test trigger installation.
            if(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:") && (ddl.equals("create") || ddl.equals("create-drop")))
                try(var statement=connection.createStatement()) {statement.execute("CREATE TRIGGER IF NOT EXISTS tr_correcao_append_only BEFORE UPDATE, DELETE ON tb_auditoria_correcao_frequencia FOR EACH ROW CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger'");}
          }
          verificar(source);
        }catch(java.sql.SQLException e){throw new IllegalStateException("Estrutura de auditoria ausente ou desprotegida; migração explícita necessária.",e);}
    }
    public static void verificar(DataSource source) throws java.sql.SQLException {
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            if(!"H2".equals(connection.getMetaData().getDatabaseProductName()))throw new IllegalStateException("Proteção de auditoria exige validação própria no SGBD escolhido.");
            try(var rows=statement.executeQuery("select count(*) from information_schema.triggers where trigger_name='TR_CORRECAO_APPEND_ONLY' and event_object_table='TB_AUDITORIA_CORRECAO_FREQUENCIA' and action_timing='BEFORE' and event_manipulation in ('UPDATE','DELETE') and java_class='com.pet.buscaativa.migration.AuditAppendOnlyTrigger'")) {
                rows.next();if(rows.getInt(1)!=2)throw new IllegalStateException("Auditoria sem proteção append-only; inicialização bloqueada.");
            }
        }
    }
}
