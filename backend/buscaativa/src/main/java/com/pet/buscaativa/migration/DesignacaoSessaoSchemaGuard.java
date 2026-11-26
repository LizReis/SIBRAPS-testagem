package com.pet.buscaativa.migration;

import java.util.List;

import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class DesignacaoSessaoSchemaGuard {
    private static final String ERRO = "Banco operacional requer a designação de sessão V32 completa.";

    private DesignacaoSessaoSchemaGuard() {}

    public static void verificar(DataSource fonte) {
        var jdbc = new JdbcTemplate(fonte);
        try {
            var versoes = jdbc.queryForList("""
                    select "version" from "sibraps_agenda_schema_history"
                    where "version" in ('31', '32') and "success" = true order by "version"
                    """, String.class);
            // As instalações de ensaio anteriores à V31 conservam seus portões próprios.
            if (versoes.isEmpty()) return;
            if (!versoes.equals(List.of("31", "32"))) throw new IllegalStateException(ERRO);
            jdbc.execute("select profissional_designado_id from tb_sessao_grupo where 1=0");
            jdbc.execute("select profissional_designado_id from tb_sessao_grupo_aud where 1=0");
        } catch (DataAccessException erro) {
            throw new IllegalStateException(ERRO, erro);
        }
    }

}
