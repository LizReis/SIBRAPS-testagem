package com.pet.buscaativa.migration;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit offline tool. Never invoked by application startup or HTTP. H2 file databases only. */
public final class AgendaMigrationTool {
    private AgendaMigrationTool() {}

    public static MigrateResult migrar(DataSource source) throws java.sql.SQLException {
        return migrar(source, "16");
    }

    /** V17 explicitamente autorizada, somente sobre V16 concluída e banco offline. */
    public static MigrateResult migrarIdentidade(DataSource source) throws java.sql.SQLException {
        return migrar(source, "17");
    }

    /** Instala somente as estruturas prospectivas, após V17 e sem definir marco. */
    public static MigrateResult migrarTransicao(DataSource source) throws java.sql.SQLException {
        return migrar(source, "18");
    }

    /** Instala a trilha prospectiva de inventário; não ativa o marco histórico. */
    public static MigrateResult migrarCoberturaHistorica(DataSource source) throws java.sql.SQLException {
        return migrar(source, "19");
    }

    /** Instala a estrutura do marco técnico sem ativar a coleta. */
    public static MigrateResult migrarMarcoHistorico(DataSource source) throws java.sql.SQLException {
        return migrar(source, "20");
    }

    /** Estrutura de evidências clínicas independentes, sem ativar o marco. */
    public static MigrateResult migrarEvidenciaClinica(DataSource source) throws java.sql.SQLException {
        return migrar(source, "21");
    }

    private static MigrateResult migrar(DataSource source, String destino) throws java.sql.SQLException {
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
                .baselineOnMigrate(true).baselineVersion("0").target(destino).cleanDisabled(true).load();
        if (!destino.equals("16")) {
            var atual = flyway.info().current();
            var versao = atual == null ? "" : atual.getVersion().getVersion();
            if (destino.equals("21")) {
                if (!java.util.Set.of("20", "21").contains(versao))
                    throw new IllegalStateException("V21 requer V20 concluída.");
                IdentidadeDocumentalPreflight.verificarOperacional(source);
                TransicaoLocalSchemaGuard.verificar(source);
                InventarioHistoricoSchemaGuard.verificar(source);
                MarcoHistoricoSchemaGuard.verificar(source);
            } else if (destino.equals("20")) {
                if (!java.util.Set.of("19", "20", "21").contains(versao))
                    throw new IllegalStateException("V20 requer V19 concluída.");
                IdentidadeDocumentalPreflight.verificarOperacional(source);
                TransicaoLocalSchemaGuard.verificar(source);
                InventarioHistoricoSchemaGuard.verificar(source);
            } else if (destino.equals("19")) {
                if (!java.util.Set.of("18", "19", "20", "21").contains(versao))
                    throw new IllegalStateException("V19 requer V18 concluída.");
                IdentidadeDocumentalPreflight.verificarOperacional(source);
                TransicaoLocalSchemaGuard.verificar(source);
            } else if (destino.equals("18")) {
                if (!java.util.Set.of("17", "18", "19", "20", "21").contains(versao))
                    throw new IllegalStateException("V18 requer V17 concluída.");
                IdentidadeDocumentalPreflight.verificarOperacional(source);
            } else if (!java.util.Set.of("16", "17", "18", "19", "20", "21").contains(versao)) {
                throw new IllegalStateException("V17 requer V16 concluída antes do diagnóstico documental.");
            }
            if (versao.equals("16")) {
                var diagnostico = IdentidadeDocumentalPreflight.analisar(source);
                if (diagnostico.bloqueiaV17())
                    throw new IllegalStateException("V17 recusada: documentos duplicados ou inconclusivos. Revisão cadastral explícita necessária. " + diagnostico);
            }
        }
        var result = flyway.migrate();
        if (!destino.equals("16")) IdentidadeDocumentalPreflight.verificarRestricoes(source);
        if (destino.equals("18") || destino.equals("19") || destino.equals("20") || destino.equals("21")) TransicaoLocalSchemaGuard.verificar(source);
        if (destino.equals("19") || destino.equals("20") || destino.equals("21")) InventarioHistoricoSchemaGuard.verificar(source);
        if (destino.equals("20") || destino.equals("21")) MarcoHistoricoSchemaGuard.verificar(source);
        if (destino.equals("21")) EvidenciaClinicaSchemaGuard.verificar(source);
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
        if (args.length == 1 && "--registrar-marco-historico".equals(args[0])) {
            var zona = java.time.ZoneId.of(System.getenv().getOrDefault("APP_ALERTAS_ZONE", "America/Bahia"));
            var marco = MarcoHistoricoOffline.registrar(source, zona);
            System.out.println("Marco técnico: " + marco.iniciadoEm() + "; unidades=" + marco.unidades()
                    + "; vínculos=" + marco.vinculos());
            return;
        }
        if (args.length == 1 && "--diagnostico-cobertura-historica".equals(args[0])) {
            EvidenciaClinicaSchemaGuard.verificar(source);
            System.out.println(ReconciliacaoEvidenciaClinica.diagnosticar(source));
            return;
        }
        if (args.length > 1) throw new IllegalArgumentException("Argumento desconhecido.");
        var result = switch (args.length == 0 ? "" : args[0]) {
            case "" -> migrar(source);
            case "--identidade" -> migrarIdentidade(source);
            case "--transicao" -> migrarTransicao(source);
            case "--cobertura-historica" -> migrarCoberturaHistorica(source);
            case "--estrutura-marco-historico" -> migrarMarcoHistorico(source);
            case "--evidencia-clinica-historica" -> migrarEvidenciaClinica(source);
            default -> throw new IllegalArgumentException("Argumento desconhecido.");
        };
        System.out.println("Migrações de agenda executadas: " + result.migrationsExecuted);
    }
    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Variável obrigatória: " + name);
        return value;
    }
}
