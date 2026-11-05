package com.pet.buscaativa;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import javax.sql.DataSource;
import org.h2.tools.RunScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class LegacyMigrationFixture {
    private LegacyMigrationFixture() {}
    static DriverManagerDataSource empty(Path path) {
        return new DriverManagerDataSource("jdbc:h2:file:" + path.toAbsolutePath(), "sa", UUID.randomUUID().toString());
    }
    static DriverManagerDataSource legacy(Path path) throws Exception {
        var source = empty(path);
        try (var connection = source.getConnection(); var reader = new InputStreamReader(
                Objects.requireNonNull(LegacyMigrationFixture.class.getResourceAsStream("/fixtures/legacy-agenda-v0.sql")), StandardCharsets.UTF_8)) {
            RunScript.execute(connection, reader);
        }
        return source;
    }
    static Map<String, List<String>> columns(DataSource source) {
        var jdbc = new JdbcTemplate(source); var result = new TreeMap<String, List<String>>();
        for (String table : jdbc.queryForList("select table_name from information_schema.tables where table_schema='PUBLIC'", String.class)) {
            result.put(table, jdbc.queryForList("select column_name from information_schema.columns where table_schema='PUBLIC' and table_name=? order by ordinal_position", String.class, table));
        }
        return result;
    }
    static Map<String, List<Map<String, Object>>> snapshot(DataSource source, Map<String, List<String>> columns) {
        var jdbc = new JdbcTemplate(source); var result = new TreeMap<String, List<Map<String, Object>>>();
        columns.forEach((table, names) -> {
            var selected = names.stream().map(LegacyMigrationFixture::quote).collect(java.util.stream.Collectors.joining(","));
            result.put(table, jdbc.queryForList("select " + selected + " from " + quote(table) + " order by " + selected));
        });
        return result;
    }
    static void backup(DataSource source, Path path) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SCRIPT TO '" + path.toAbsolutePath().toString().replace("'", "''") + "'");
        }
    }
    static DriverManagerDataSource restore(Path path, Path script) throws Exception {
        var source = empty(path);
        try (var connection = source.getConnection(); var reader = Files.newBufferedReader(script)) { RunScript.execute(connection, reader); }
        return source;
    }
    private static String quote(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }
}
