package com.pet.buscaativa.migration;

import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

/** Validation only: persistent data are never inferred or migrated during application startup. */
@Component @RequiredArgsConstructor
public class ResponsabilidadeSessaoGuard implements SmartInitializingSingleton {
    private final DataSource source;
    @Override public void afterSingletonsInstantiated(){try{verificar(source);}catch(java.sql.SQLException e){throw new IllegalStateException("Responsabilidade histórica exige migração explícita.",e);}}
    public static void verificar(DataSource source) throws java.sql.SQLException {
        try(var c=source.getConnection();var q=c.createStatement();var rows=q.executeQuery("select count(*) from tb_sessao_grupo where status='REALIZADA' and not ((responsavel_atendimento_id is not null and responsabilidade_pendente=false and motivo_responsabilidade_pendente is null) or (responsavel_atendimento_id is null and responsabilidade_pendente=true and motivo_responsabilidade_pendente is not null))")){
            rows.next();if(rows.getInt(1)!=0)throw new IllegalStateException("Sessões realizadas sem responsabilidade histórica conhecida ou pendência explícita.");
        }
    }
}
