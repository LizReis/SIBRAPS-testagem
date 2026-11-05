package com.pet.buscaativa.migration;

import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.services.PacienteService;
import lombok.RequiredArgsConstructor;

/** Rebuild derived caches with the SAME domain policy after an explicit migration, before readiness.
 * One patient lock/transaction at a time; no appointments, roles or clinical events are created.
 */
@Component
@RequiredArgsConstructor
public class AgendaMigratedIndicatorsInitializer implements ApplicationRunner {
    private final DataSource source;
    private final PacienteRepository patients;
    private final PacienteService clinical;
    private final PlatformTransactionManager transactions;

    @Override public void run(ApplicationArguments args) throws Exception {
        try (var connection = source.getConnection()) {
            if (!"H2".equals(connection.getMetaData().getDatabaseProductName())) return;
            try (var tables = connection.getMetaData().getTables(null, connection.getSchema(), "sibraps_agenda_schema_history", null)) {
                if (!tables.next()) return;
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "select count(*) from \"sibraps_agenda_schema_history\" where \"version\"='2' and \"success\"=true")) {
                rows.next(); if (rows.getInt(1) != 1) return;
            }
        }
        for (var id : patients.findIdsComIndicadoresPendentes()) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                var patient = patients.findByIdPublicoForUpdate(id).orElseThrow();
                // Technical refresh: Envers/SISTEMA records cache changes, not a new clinical fact.
                clinical.recalcularAssiduidadePaciente(patient, false);
            });
        }
    }
}
