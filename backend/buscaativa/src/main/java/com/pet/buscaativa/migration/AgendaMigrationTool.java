package com.pet.buscaativa.migration;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit offline tool. Never invoked by application startup or HTTP. H2 file databases only. */
public final class AgendaMigrationTool {
    private AgendaMigrationTool() {}

    public static MigrateResult migrar(DataSource source) throws java.sql.SQLException {
        return migrar(source, false);
    }

    /** V17 explicitamente autorizada, somente sobre V16 concluída e banco offline. */
    public static MigrateResult migrarIdentidade(DataSource source) throws java.sql.SQLException {
        return migrar(source, true);
    }

    private static MigrateResult migrar(DataSource source, boolean identidade) throws java.sql.SQLException {
        try (var connection = source.getConnection()) {
            if (!"H2".equals(connection.getMetaData().getDatabaseProductName())
                    || connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"))
                throw new IllegalArgumentException("Migração validada somente para H2 persistido e isolado; outro SGBD exige validação própria.");
            try (var statement = connection.createStatement()) {
                try (var settings = statement.executeQuery("select setting_value from information_schema.settings where setting_name='DB_CLOSE_DELAY'")) {
                    if (settings.next() && !"0".equals(settings.getString(1)))
                        throw new IllegalArgumentException("A ferramenta H2 em arquivo foi validada com DB_CLOSE_DELAY=0; outro ciclo de vida exige ensaio separado.");
                }
                // Refuse an empty/wrong database before Flyway creates any schema/history.
                for (String table : new String[]{"tb_agendamento", "tb_agendamento_aud", "tb_historico_paciente", "tb_paciente", "tb_paciente_aud", "tb_usuario", "revinfo", "tb_sessao_grupo", "tb_sessao_grupo_participante"})
                    statement.executeQuery("select count(*) from " + table).close();
            }
            verificarOrigemVersionada(connection);
        }
        var flyway = Flyway.configure().dataSource(source).table("sibraps_agenda_schema_history")
                .locations("classpath:agenda-migration/h2").javaMigrations(new V2__identificar_frequencias_legadas(), new V6__identificar_responsabilidade_historica())
                .baselineOnMigrate(true).baselineVersion("0").target(identidade ? "17" : "16").cleanDisabled(true).load();
        if (identidade) {
            var atual = flyway.info().current();
            if (atual == null || !java.util.Set.of("16", "17").contains(atual.getVersion().getVersion()))
                throw new IllegalStateException("V17 requer V16 concluída antes do diagnóstico documental.");
            if (atual.getVersion().getVersion().equals("16")) {
                var diagnostico = IdentidadeDocumentalPreflight.analisar(source);
                if (diagnostico.bloqueiaV17())
                    throw new IllegalStateException("V17 recusada: documentos duplicados ou inconclusivos. Revisão cadastral explícita necessária. " + diagnostico);
            }
        }
        var result = flyway.migrate();
        if (identidade) IdentidadeDocumentalPreflight.verificarRestricoes(source);
        CanonicalAgendaGuard.verificar(source);
        AuditoriaSchemaGuard.verificar(source);
        ResponsabilidadeSessaoGuard.verificar(source);
        return result;
    }

    private static void verificarOrigemVersionada(java.sql.Connection connection) throws java.sql.SQLException {
        try (var columns = connection.getMetaData().getColumns(null, connection.getSchema(), "TB_AGENDAMENTO", "MIGRACAO_PENDENTE")) {
            if (!columns.next()) return;
        }
        boolean initialized;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("select count(*) from tb_agendamento where migracao_pendente is not null")) {
            rows.next(); initialized = rows.getLong(1) > 0;
        }
        if (!initialized) return; // Additive columns without backfill are still an identifiable legacy input.
        boolean completed = false;
        try (var tables = connection.getMetaData().getTables(null, connection.getSchema(), "sibraps_agenda_schema_history", null)) {
            if (tables.next()) {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                        "select count(*) from \"sibraps_agenda_schema_history\" where \"version\"='2' and \"success\"=true")) {
                    rows.next(); completed = rows.getLong(1) == 1;
                }
            }
        }
        if (!completed) throw new IllegalStateException("Existem estados canônicos sem origem versionada confirmada; backfill recusado sem alterar dados.");
    }

    public static void main(String[] args) throws Exception {
        var source = new DriverManagerDataSource(required("DB_URL"), required("DB_USERNAME"), required("DB_PASSWORD"));
        if (args.length == 1 && "--diagnostico-identidade".equals(args[0])) {
            System.out.println(IdentidadeDocumentalPreflight.analisar(source));
            return;
        }
        if (args.length > 1 || args.length == 1 && !"--identidade".equals(args[0]))
            throw new IllegalArgumentException("Argumento desconhecido.");
        var result = args.length == 1 ? migrarIdentidade(source) : migrar(source);
        System.out.println("Migrações de agenda executadas: " + result.migrationsExecuted);
    }
    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Variável obrigatória: " + name);
        return value;
    }
}
