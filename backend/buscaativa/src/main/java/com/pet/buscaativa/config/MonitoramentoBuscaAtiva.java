package com.pet.buscaativa.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.services.PacienteService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;

/** Compatibilidade legada até o corte coordenado; não executa o modelo local. */
@Component
@RequiredArgsConstructor
public class MonitoramentoBuscaAtiva {
    private static final Logger log = LoggerFactory.getLogger(MonitoramentoBuscaAtiva.class);
    private final PacienteRepository pacienteRepository;
    private final PacienteService pacienteService;
    private final PlatformTransactionManager transactions;
    private final EntityManager em;

    @Scheduled(cron = "0 0 1 * * ?")
    public void atualizarRiscosDiariamente() {
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var ids = tx.execute(s -> pacienteRepository.findIdsPorStatus(StatusPaciente.ATIVO));
        int falhas = 0;
        for (var id : ids) {
            try {
                tx.executeWithoutResult(s -> {
                    var p = pacienteRepository.findByIdPublicoForUpdate(id).orElse(null);
                    if (p == null) return;
                    em.refresh(p, LockModeType.PESSIMISTIC_WRITE);
                    if (p.getStatusPaciente() != StatusPaciente.ATIVO) return;
                    pacienteService.calcularEAtualizarRisco(p);
                    // Entidade gerenciada: não salvar novamente a lista desatualizada.
                    em.flush();
                });
            } catch (RuntimeException ex) {
                falhas++;
                log.error("Falha técnica ao classificar paciente: {}", ex.getClass().getSimpleName());
            }
        }
        if (falhas > 0)
            throw new IllegalStateException("Classificação de risco concluída com " + falhas + " falha(s).");
    }
}
