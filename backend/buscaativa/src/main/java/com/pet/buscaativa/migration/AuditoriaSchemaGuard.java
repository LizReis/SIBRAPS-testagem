package com.pet.buscaativa.migration;

import javax.sql.DataSource;
import java.util.Locale;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component @RequiredArgsConstructor
public class AuditoriaSchemaGuard implements SmartInitializingSingleton {
    private static final String[][] TABLES={{"tb_auditoria_correcao_frequencia","tr_correcao_append_only"},{"tb_auditoria_busca_ativa","tr_busca_ativa_append_only"},{"tb_auditoria_regularizacao_cancelamento","tr_regularizacao_append_only"}};
    private final DataSource source;
    private final Environment environment;
    @Override public void afterSingletonsInstantiated() {
        try {
            try(var connection=source.getConnection()) {
                var ddl=environment.getProperty("spring.jpa.hibernate.ddl-auto","");
                // Never install/repair protections implicitly in persistent databases.
                if(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:") && (ddl.equals("create") || ddl.equals("create-drop")))
                    try(var statement=connection.createStatement()) {
                        for(var entry:TABLES)statement.execute("CREATE TRIGGER IF NOT EXISTS "+entry[1]+" BEFORE UPDATE, DELETE ON "+entry[0]+" FOR EACH ROW CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger'");
                        statement.execute("ALTER TABLE tb_auditoria_regularizacao_cancelamento ADD CONSTRAINT IF NOT EXISTS fk_regularizacao_identidade FOREIGN KEY(agendamento_id,paciente_id,responsavel_id) REFERENCES tb_agendamento(id,paciente_id,usuario_id)");
                    }
            }
            verificar(source); // Release the connection first, also works with a one-connection pool.
        }catch(java.sql.SQLException e){throw new IllegalStateException("Estrutura de auditoria ausente ou desprotegida; migração explícita necessária.",e);}
    }
    public static void verificar(DataSource source) throws java.sql.SQLException {
        try(var connection=source.getConnection()) {
            if(!"H2".equals(connection.getMetaData().getDatabaseProductName()))throw new IllegalStateException("Proteção de auditoria exige validação própria no SGBD escolhido.");
            int identityColumns=0;
            try(var keys=connection.getMetaData().getImportedKeys(null,connection.getSchema(),"TB_AUDITORIA_REGULARIZACAO_CANCELAMENTO")) {
                while(keys.next())if("FK_REGULARIZACAO_IDENTIDADE".equalsIgnoreCase(keys.getString("FK_NAME")))identityColumns++;
            }
            if(identityColumns!=3)throw new IllegalStateException("Auditoria de regularização sem integridade da identidade assistencial.");
            for(var entry:TABLES)try(var query=connection.prepareStatement("select count(*) from information_schema.triggers where trigger_name=? and event_object_table=? and event_object_schema=? and action_timing='BEFORE' and event_manipulation in ('UPDATE','DELETE') and java_class='com.pet.buscaativa.migration.AuditAppendOnlyTrigger'")) {
                query.setString(1,entry[1].toUpperCase(Locale.ROOT));query.setString(2,entry[0].toUpperCase(Locale.ROOT));query.setString(3,connection.getSchema());
                try(var rows=query.executeQuery()){rows.next();if(rows.getInt(1)!=2)throw new IllegalStateException("Auditoria sem proteção append-only; inicialização bloqueada.");}
            }
        }
    }
}
