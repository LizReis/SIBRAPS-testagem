package com.pet.buscaativa.migration;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import com.pet.buscaativa.entities.PoliticaOcupacaoAgendamento;
import com.pet.buscaativa.entities.enums.*;

/** Immutable versioned backfill. Changes to this mapping require a NEW migration version. */
public class V2__identificar_frequencias_legadas extends BaseJavaMigration {
    @Override public Integer getChecksum() { return 202611052; }

    record Legacy(long id, long patient, Integer code, Long original, boolean missedReschedule, LocalDateTime when) {}
    record Evidence(FrequenciaAssistencial frequency, LocalDateTime changed, boolean contradictory) {}
    record Mapping(SituacaoReserva reservation, FrequenciaAssistencial attendance, String pending) {}

    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        var records = new LinkedHashMap<Long, Legacy>();
        try (var query = connection.createStatement(); var rows = query.executeQuery("select * from tb_agendamento order by id")) {
            while (rows.next()) {
                var date = rows.getDate("data_agendamento"); var time = rows.getTime("hora_atendimento");
                var row = new Legacy(rows.getLong("id"), rows.getLong("paciente_id"),
                        rows.getObject("situacao_atendimento", Integer.class), rows.getObject("agendamento_original_id", Long.class),
                        rows.getBoolean("remarcacao_apos_falta"), date == null || time == null ? null : LocalDateTime.of(date.toLocalDate(), time.toLocalTime()));
                records.put(row.id(), row);
            }
        }
        var evidence = evidence(connection);
        var mappings = new HashMap<Long, Mapping>();
        for (var row : records.values()) mappings.put(row.id(), map(row, records, evidence.get(row.id())));
        for (var row : records.values()) {
            var mapped = mappings.get(row.id()); var parent = mappings.get(row.original());
            if (mapped.pending() == null && parent != null && !row.missedReschedule()
                    && parent.attendance() == FrequenciaAssistencial.FALTOU) {
                // The fact is known, but the booking-time context contradicts/does not explain the flag.
                // Preserve both pieces of evidence instead of rewriting provenance or certifying the alert.
                mappings.put(row.id(), new Mapping(mapped.reservation(), mapped.attendance(), "CONTEXTO_REMARCACAO_NAO_IDENTIFICADO"));
            }
        }
        try (var update = connection.prepareStatement("""
                update tb_agendamento set situacao_reserva=?, frequencia_assistencial=?, migracao_pendente=?,
                motivo_migracao_pendente=?, ocupa_vaga=? where id=?
                """)) {
            for (var row : records.values()) {
                var mapped = mappings.get(row.id());
                boolean pending = mapped.pending() != null;
                update.setString(1, mapped.reservation() == null ? null : mapped.reservation().name());
                update.setString(2, mapped.attendance() == null ? null : mapped.attendance().name());
                update.setBoolean(3, pending); update.setString(4, mapped.pending());
                update.setBoolean(5, pending ? PoliticaOcupacaoAgendamento.ocupavaLegado(row.code())
                        : PoliticaOcupacaoAgendamento.ocupa(mapped.reservation(), mapped.attendance()));
                update.setLong(6, row.id());
                if (update.executeUpdate() != 1) throw new SQLException("Backfill concorrente inesperado; nenhuma correção clínica automática é permitida.");
            }
        }
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                update tb_paciente set indicadores_pendentes=true where id in
                (select paciente_id from tb_agendamento)
                """);
        }
        // No clinical events, revisions, versions, identities or preexisting patient indicators are rewritten.
    }

    private static Mapping map(Legacy row, Map<Long, Legacy> records, Evidence evidence) {
        Integer code = row.code();
        if (code == null || code < 1 || code > 6) return new Mapping(null, null, "SITUACAO_LEGADA_NAO_IDENTIFICADA");
        SituacaoReserva reserve = switch (code) {
            case 2 -> SituacaoReserva.AGENDADA;
            case 4 -> SituacaoReserva.REMARCADA;
            case 5 -> SituacaoReserva.CANCELADA;
            case 6 -> SituacaoReserva.SUBSTITUIDA;
            default -> row.original() == null ? SituacaoReserva.AGENDADA : SituacaoReserva.REMARCADA;
        };
        FrequenciaAssistencial frequency = switch (code) {
            case 1 -> FrequenciaAssistencial.PRESENTE;
            case 3 -> FrequenciaAssistencial.FALTOU;
            case 2, 4 -> FrequenciaAssistencial.NAO_REGISTRADA;
            default -> evidence == null ? null : evidence.frequency();
        };
        String pending = validateChain(row, records);
        if (code == 4 && row.original() == null) pending = "REMARCACAO_SEM_ORIGINAL";
        if (code == 2 && row.original() != null) pending = "RESERVA_LEGADA_COM_ORIGEM_INCOMPATIVEL";
        if (row.missedReschedule() && row.original() == null) pending = "FLAG_SEM_VINCULO";
        if (row.when() == null) pending = "CRONOLOGIA_NAO_IDENTIFICADA";
        if (evidence != null && (evidence.contradictory() || (code <= 4 && frequency != evidence.frequency()))) {
            frequency = null; pending = "EVIDENCIAS_CLINICAS_CONFLITANTES";
        }
        if (frequency == null && pending == null) pending = "FREQUENCIA_NAO_IDENTIFICADA";
        return new Mapping(reserve, frequency, pending);
    }

    private static String validateChain(Legacy row, Map<Long, Legacy> all) {
        var seen = new HashSet<Long>(); var current = row;
        while (current.original() != null) {
            if (!seen.add(current.id())) return "CADEIA_CIRCULAR";
            var parent = all.get(current.original());
            if (parent == null) return "ORIGINAL_NAO_IDENTIFICADO";
            if (parent.patient() != row.patient()) return "PACIENTES_DIVERGENTES";
            if (parent.when() == null || current.when() == null || !parent.when().isBefore(current.when()))
                return "CRONOLOGIA_INCOERENTE";
            current = parent;
        }
        return null;
    }

    private static Map<Long, Evidence> evidence(Connection connection) throws SQLException {
        var result = new HashMap<Long, Evidence>();
        try (var query = connection.createStatement(); var rows = query.executeQuery("""
                select agendamento_id, tipo, situacao_atendimento, alterado_em, criado_em, ocorrido_em
                from tb_historico_paciente where agendamento_id is not null order by id
                """)) {
            while (rows.next()) {
                var type = rows.getString("tipo");
                if (!Set.of("PRESENCA", "FALTA", "PARTICIPACAO_GRUPO_TERAPEUTICO").contains(type)) continue;
                Integer state = rows.getObject("situacao_atendimento", Integer.class);
                boolean mismatch = state == null || (state != 1 && state != 3)
                        || ("FALTA".equals(type) != (state == 3));
                var date = rows.getTimestamp("alterado_em");
                if (date == null) date = rows.getTimestamp("criado_em");
                if (date == null) date = rows.getTimestamp("ocorrido_em");
                var changed = date == null ? LocalDateTime.MIN : date.toLocalDateTime();
                var next = new Evidence(mismatch ? null : state == 1 ? FrequenciaAssistencial.PRESENTE : FrequenciaAssistencial.FALTOU,
                        changed, mismatch);
                long id = rows.getLong("agendamento_id"); var previous = result.get(id);
                if (previous == null || changed.isAfter(previous.changed())) result.put(id, next);
                else if (changed.equals(previous.changed()) && (next.contradictory() || previous.frequency() != next.frequency()))
                    result.put(id, new Evidence(null, changed, true));
            }
        }
        // A real clinical Envers revision can corroborate an outcome when no linked clinical event survived.
        // Operational revisions (2/4/5/6) and a provenance flag alone never become attendance facts.
        var revisions = new HashMap<Long, Evidence>();
        try (var query = connection.createStatement(); var rows = query.executeQuery("""
                select id, situacao_atendimento from tb_agendamento_aud
                where situacao_atendimento in (1,3) and revtype <> 2 order by id, rev
                """)) {
            while (rows.next()) revisions.put(rows.getLong("id"), new Evidence(
                    rows.getInt("situacao_atendimento") == 1 ? FrequenciaAssistencial.PRESENTE : FrequenciaAssistencial.FALTOU,
                    LocalDateTime.MIN, false));
        }
        revisions.forEach(result::putIfAbsent);
        return result;
    }
}
