package com.pet.buscaativa.migration;

import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import com.pet.buscaativa.entities.PoliticaOcupacaoAgendamento;
import com.pet.buscaativa.entities.enums.SituacaoReserva;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;

/** Fail closed before serving requests if backfill was not completed. Does not migrate or repair. */
@Component
@RequiredArgsConstructor
public class CanonicalAgendaGuard implements SmartInitializingSingleton {
    private final DataSource source;
    @Override public void afterSingletonsInstantiated() {
        try { verificar(source); }
        catch (java.sql.SQLException e) { throw new IllegalStateException("Schema de agenda não validado; migração explícita é necessária.", e); }
    }
    public static void verificar(DataSource source) throws java.sql.SQLException {
        try (var connection = source.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("""
                    select migracao_pendente, ocupa_vaga, situacao_reserva, frequencia_assistencial,
                    situacao_atendimento, motivo_migracao_pendente from tb_agendamento
                    """)) {
            while (rows.next()) {
                var pending = rows.getObject("migracao_pendente", Boolean.class);
                var occupied = rows.getObject("ocupa_vaga", Boolean.class);
                var legacy = rows.getObject("situacao_atendimento", Integer.class);
                boolean valid = pending != null && occupied != null;
                if (valid && pending) {
                    var reason = rows.getString("motivo_migracao_pendente");
                    valid = reason != null && !reason.isBlank()
                            && occupied == PoliticaOcupacaoAgendamento.ocupavaLegado(legacy);
                } else if (valid) {
                    try {
                        var reservation = SituacaoReserva.valueOf(rows.getString("situacao_reserva"));
                        var attendance = FrequenciaAssistencial.valueOf(rows.getString("frequencia_assistencial"));
                        valid = java.util.Objects.equals(legacy, PoliticaOcupacaoAgendamento.projetar(reservation, attendance).getCodigo())
                                && occupied == PoliticaOcupacaoAgendamento.ocupa(reservation, attendance);
                    } catch (IllegalArgumentException | NullPointerException exception) { valid = false; }
                }
                if (!valid) throw new IllegalStateException("Migração de agenda incompleta ou incoerente; inicialização bloqueada sem alterar dados.");
            }
        }
    }
}
