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
 * A tabela é instalada exclusivamente por fixture de teste, nunca por este serviço.
 * Uma reserva confirmada antes da simulação não é automaticamente reenviada:
 * falha ambígua/crash exige revisão. Não promete exactly-once de transporte externo.
 */
@Service
@Profile("test & ensaio-estado-local")
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
        try {
            tx.executeWithoutResult(s->jdbc.update("""
                    insert into ensaio_entrega_rf26 (dia,destinatario,unidade,estado)
                    values (?,?,?,'RESERVADA')
                    """,chave.data(),chave.destinatario(),chave.unidade()));
        } catch(DuplicateKeyException duplicada) { return Resultado.DUPLICADA; }

        Resultado resultado;
        try {
            var entrega=notificacoes.revalidar(chave);
            if(entrega.isEmpty()) resultado=Resultado.INVALIDADA;
            else {
                // Injeção exclusiva para ensaiar falhas de transporte, sem bean de e-mail.
                simulador.simular(entrega.orElseThrow());
                resultado=Resultado.SIMULADA;
            }
        } catch(RuntimeException falha) { resultado=Resultado.INCERTA; }
        final Resultado finalizado=resultado;
        tx.executeWithoutResult(s->{
            int alteradas=jdbc.update("""
                    update ensaio_entrega_rf26 set estado=? where dia=? and destinatario=? and unidade=? and estado='RESERVADA'
                    """,finalizado.name(),chave.data(),chave.destinatario(),chave.unidade());
            if(alteradas!=1) throw new IllegalStateException("Reserva técnica não localizada.");
        });
        return finalizado;
    }
}
