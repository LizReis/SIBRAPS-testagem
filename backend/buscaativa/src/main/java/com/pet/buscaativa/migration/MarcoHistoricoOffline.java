package com.pet.buscaativa.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;

/** Marco técnico explícito. Apenas banco H2 em arquivo, offline, sem sessões concorrentes. */
public final class MarcoHistoricoOffline {
    private MarcoHistoricoOffline() {}

    public record Marco(OffsetDateTime iniciadoEm, String zona, long ultimoMovimento, int unidades, long vinculos) {}
    public record Vinculo(long id, long paciente) {}

    public static Marco registrar(DataSource fonte, ZoneId zona) throws SQLException {
        Objects.requireNonNull(zona, "Zona institucional obrigatória.");
        if (zona.getId().length() > 80) throw new IllegalArgumentException("Zona institucional inválida.");
        try (var c = fonte.getConnection()) {
            if (!"H2".equals(c.getMetaData().getDatabaseProductName())
                    || !c.getMetaData().getURL().startsWith("jdbc:h2:file:")
                    || !c.getAutoCommit())
                throw new IllegalArgumentException("O marco exige H2 em arquivo, conexão administrativa exclusiva e banco offline.");
            try (var s = c.createStatement()) {
                // Modo 1 não encerra conexões existentes nem descarta suas transações.
                s.execute("SET EXCLUSIVE 1");
                try {
                    var jdbc = new JdbcTemplate(new SingleConnectionDataSource(c, true));
                    if (jdbc.queryForObject("select count(*) from information_schema.sessions where session_id <> session_id()", Long.class) != 0)
                        throw new IllegalStateException("Encerre todas as outras sessões antes de registrar o marco.");
                    c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                    c.setAutoCommit(false);
                    try {
                        var marco = registrarInterno(jdbc, zona);
                        c.commit();
                        return marco;
                    } catch (RuntimeException | SQLException falha) {
                        c.rollback();
                        throw falha;
                    }
                } finally {
                    // A transação já deve ter sido confirmada ou revertida antes do comando H2.
                    c.setAutoCommit(true);
                    s.execute("SET EXCLUSIVE 0");
                }
            }
        }
    }

    private static Marco registrarInterno(JdbcTemplate jdbc, ZoneId zona) throws SQLException {
        var fonte = Objects.requireNonNull(jdbc.getDataSource());
        TransicaoLocalSchemaGuard.verificar(fonte);
        InventarioHistoricoSchemaGuard.verificar(fonte);
        MarcoHistoricoSchemaGuard.verificar(fonte);
        EvidenciaClinicaSchemaGuard.verificar(fonte);
        boolean evidenciaClinica = EvidenciaClinicaSchemaGuard.instalada(jdbc);
        var versoes = jdbc.queryForList("""
                select "version" from "sibraps_agenda_schema_history"
                where "version" in ('18','19','20') and "success"=true order by "version"
                """, String.class);
        if (!versoes.equals(List.of("18", "19", "20")))
            throw new IllegalStateException("O marco requer V18, V19 e V20 concluídas.");
        IdentidadeDocumentalPreflight.verificarOperacional(fonte);
        var anteriores = jdbc.query("""
                select iniciado_em,zona,ultimo_movimento,total_unidades,total_vinculos
                from tb_marco_tecnico_historico where id=1
                """, (rs, n) -> new Marco(rs.getObject(1, OffsetDateTime.class), rs.getString(2),
                        rs.getLong(3), rs.getInt(4), rs.getLong(5)));
        if (!anteriores.isEmpty()) {
            var anterior = anteriores.getFirst();
            if (!anterior.zona().equals(zona.getId()))
                throw new IllegalStateException("A zona de um marco já registrado não pode ser alterada.");
            if (evidenciaClinica && !BaseEvidenciaClinicaOffline.existe(jdbc))
                throw new IllegalStateException("Marco anterior sem base clínica contemporânea; cobertura retroativa recusada.");
            return anterior;
        }
        var instante = Objects.requireNonNull(jdbc.queryForObject("select current_timestamp", OffsetDateTime.class))
                .truncatedTo(ChronoUnit.MICROS);
        long movimento = jdbc.queryForObject("select coalesce(max(id),0) from tb_movimento_inventario_historico", Long.class);
        Map<String, List<Vinculo>> inventario = new TreeMap<>();
        // Unidades com lotação configurada, mesmo vazias. Não criar cobertura para unidade futura.
        for (String unidade : jdbc.queryForList("""
                select distinct case
                    when unidade_atuacao=1 and usf_atuacao_id in (select id from tb_usf_referencia) then concat('USF:',cast(usf_atuacao_id as varchar))
                    when unidade_atuacao=2 and usf_atuacao_id is null then 'CAPS_AD'
                    when unidade_atuacao=3 and usf_atuacao_id is null then 'CAPS_I'
                    when unidade_atuacao=4 and usf_atuacao_id is null then 'CAPS_II'
                    else null end as unidade from tb_usuario
                """, String.class)) {
            if (unidade != null) inventario.put(unidade, new ArrayList<>());
        }
        // Vínculos de unidades sem usuários atuais também integram a população inicial.
        jdbc.query("""
                select id,paciente_id,unidade_chave from tb_vinculo_assistencial order by id
                """, rs -> {
            String unidade = rs.getString(3);
            if (unidade == null || !(unidade.matches("USF:[1-9][0-9]*")
                    || List.of("CAPS_I", "CAPS_II", "CAPS_AD").contains(unidade)))
                throw new IllegalStateException("Vínculo com escopo inconclusivo; marco recusado.");
            inventario.computeIfAbsent(unidade, k -> new ArrayList<>()).add(new Vinculo(rs.getLong(1), rs.getLong(2)));
        });
        long total = inventario.values().stream().mapToLong(List::size).sum();
        jdbc.update("""
                insert into tb_marco_tecnico_historico
                (id,iniciado_em,zona,ultimo_movimento,total_unidades,total_vinculos,inventario)
                values (1,?,?,?,?,?,?)
                """, instante, zona.getId(), movimento, inventario.size(), total, new ObjectMapper().writeValueAsString(inventario));
        if (evidenciaClinica) BaseEvidenciaClinicaOffline.registrar(jdbc);
        return new Marco(instante, zona.getId(), movimento, inventario.size(), total);
    }
}
