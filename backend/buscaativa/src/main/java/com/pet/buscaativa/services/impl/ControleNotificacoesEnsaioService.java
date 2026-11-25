package com.pet.buscaativa.services.impl;

import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lombok.RequiredArgsConstructor;

/**
 * Protocolo experimental de reserva durável, SEM transporte SMTP.
 * A tabela técnica é instalada pela migration offline V16.
 * Uma reserva confirmada antes da simulação não é automaticamente reenviada:
 * falha ambígua/crash exige revisão. Não promete exactly-once de transporte externo.
 */
@Service
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
public class ControleNotificacoesEnsaioService {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final NotificacoesLocaisEnsaioService notificacoes;

    public enum Resultado { SIMULADA, INVALIDADA, INCERTA, DUPLICADA }
    @FunctionalInterface public interface Simulador {
        void simular(NotificacoesLocaisEnsaioService.Entrega entrega);
    }

    public Resultado executar(NotificacoesLocaisEnsaioService.Chave chave,Simulador simulador) {
        if(SecurityContextHolder.getContext().getAuthentication()!=null
                || TransactionSynchronizationManager.isActualTransactionActive())
            throw new AccessDeniedException("Execução técnica exige contexto independente.");
        Objects.requireNonNull(chave); Objects.requireNonNull(simulador);
        Objects.requireNonNull(chave.data()); Objects.requireNonNull(chave.destinatario()); Objects.requireNonNull(chave.unidade());
        var tx=new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        if (Boolean.TRUE.equals(tx.execute(s -> jdbc.queryForObject("""
                select exists(select 1 from tb_entrega_rf26 where dia=? and destinatario=? and unidade=?)
                """, Boolean.class, chave.data(), chave.destinatario(), chave.unidade())))) return Resultado.DUPLICADA;
        try {
            tx.executeWithoutResult(s->jdbc.update("""
                    insert into tb_entrega_rf26 (dia,destinatario,unidade,estado,reservada_em)
                    values (?,?,?,'RESERVADA',current_timestamp)
                    """,chave.data(),chave.destinatario(),chave.unidade()));
        } catch(DuplicateKeyException duplicada) { return Resultado.DUPLICADA; }

        return tx.execute(s -> tentar(chave, simulador));
    }

    private Resultado tentar(NotificacoesLocaisEnsaioService.Chave chave, Simulador simulador) {
        String estado = jdbc.queryForObject("""
                select estado from tb_entrega_rf26 where dia=? and destinatario=? and unidade=? for update
                """, String.class, chave.data(), chave.destinatario(), chave.unidade());
        if (!"RESERVADA".equals(estado)) return Resultado.DUPLICADA;

        var entrega = notificacoes.revalidarParaTentativa(chave);
        Resultado resultado = Resultado.INVALIDADA;
        if (entrega.isPresent()) {
            // O adaptador fica fora da transação de dados. A suspensão conserva os locks
            // da transação externa até o adaptador devolver o resultado.
            var semTransacao = new TransactionTemplate(transactions);
            semTransacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
            try {
                semTransacao.executeWithoutResult(s -> simulador.simular(entrega.orElseThrow()));
                resultado = Resultado.SIMULADA;
            } catch (RuntimeException ambigua) {
                resultado = Resultado.INCERTA;
            }
        }
        int alteradas = jdbc.update("""
                update tb_entrega_rf26 set estado=?, finalizada_em=greatest(current_timestamp,reservada_em)
                where dia=? and destinatario=? and unidade=? and estado='RESERVADA'
                """, resultado.name(), chave.data(), chave.destinatario(), chave.unidade());
        if (alteradas != 1) throw new IllegalStateException("Reserva técnica não localizada.");
        return resultado;
    }
}
