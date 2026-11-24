package com.pet.buscaativa.services.impl;

import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;

/** Rotina interna experimental, sem agendamento automático nem rota HTTP. */
@Service
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
public class MonitoramentoVinculosEnsaioService {
    private final PlatformTransactionManager transactions;
    private final AgendaAssistencialLocks locks;
    private final VinculoAssistencialRepository vinculos;
    private final PersistenciaIndicadoresVinculoEnsaioService indicadores;
    private final EntityManager em;

    record Alvo(Long id, UUID paciente, UUID vinculo, String unidade) {}
    public record Resumo(int examinados, int atualizados, int inalterados, int inconclusivos,
            int ignorados, int falhas) {}
    private enum Resultado { ATUALIZADO, INALTERADO, INCONCLUSIVO, IGNORADO }

    public Resumo executar() {
        if (SecurityContextHolder.getContext().getAuthentication() != null
                || TransactionSynchronizationManager.isActualTransactionActive())
            throw new AccessDeniedException("Monitoramento exige contexto técnico independente.");
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<Alvo> alvos = tx.execute(s -> em.createQuery("""
                select new com.pet.buscaativa.services.impl.MonitoramentoVinculosEnsaioService$Alvo(
                    v.id, v.paciente.idPublico, v.idPublico, v.unidadeChave)
                from VinculoAssistencial v where v.statusPaciente=:ativo order by v.id
                """, Alvo.class).setParameter("ativo", StatusPaciente.ATIVO).getResultList());
        int atualizados=0, inalterados=0, inconclusivos=0, ignorados=0, falhas=0;
        for (var alvo : alvos) {
            try {
                var resultado = tx.execute(s -> recalcular(alvo));
                switch (resultado) {
                    case ATUALIZADO -> atualizados++;
                    case INALTERADO -> inalterados++;
                    case INCONCLUSIVO -> inconclusivos++;
                    case IGNORADO -> ignorados++;
                }
            } catch (RuntimeException ex) {
                falhas++;
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Falha no recálculo técnico: {}", ex.getClass().getSimpleName());
            }
        }
        return new Resumo(alvos.size(),atualizados,inalterados,inconclusivos,ignorados,falhas);
    }

    private Resultado recalcular(Alvo alvo) {
        var pessoa = locks.travarPaciente(alvo.paciente());
        var local = vinculos.findLocalForEstadoUpdate(alvo.vinculo(),alvo.paciente(),alvo.unidade()).orElse(null);
        if (local == null) return Resultado.IGNORADO;
        em.refresh(local,LockModeType.PESSIMISTIC_WRITE);
        if (!alvo.id().equals(local.getId()) || !pessoa.getId().equals(local.getPaciente().getId())
                || !alvo.unidade().equals(local.getUnidadeChave())
                || !ProjecaoClinicaVinculoService.chaveCanonica(local))
            throw CoordenacaoEscritaClinicaEnsaio.mudou();
        if (local.getStatusPaciente()!=StatusPaciente.ATIVO) return Resultado.IGNORADO;
        if (!indicadores.evidencia(local).comprovada()) return Resultado.INCONCLUSIVO;
        var resultado = indicadores.aplicarTecnico(indicadores.avaliar(local));
        em.flush();
        return switch (resultado.situacao()) {
            case ATUALIZADO -> Resultado.ATUALIZADO;
            case SEM_ALTERACAO -> Resultado.INALTERADO;
            case INCONCLUSIVO -> Resultado.INCONCLUSIVO;
        };
    }
}
