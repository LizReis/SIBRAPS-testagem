package com.pet.buscaativa.services.impl;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pet.buscaativa.utils.DocumentoUtil;

/** Serializa documentos ainda sem linha de paciente no ensaio de instância única. */
@Component
@Profile("test & ensaio-estado-local")
class DocumentosCadastroLocalLocks {
    private final List<ReentrantLock> travas = IntStream.range(0, 128).mapToObj(i -> new ReentrantLock()).toList();

    void bloquear(String cpf, String cns) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Trava documental requer transação.");
        var ordem = Stream.of(DocumentoUtil.normalizarCPF(cpf), DocumentoUtil.normalizarCNS(cns))
                .filter(Objects::nonNull).filter(d -> !d.isBlank())
                .map(d -> Math.floorMod(d.hashCode(), travas.size())).distinct().sorted().map(travas::get).toList();
        ordem.forEach(ReentrantLock::lock);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                ordem.reversed().forEach(ReentrantLock::unlock);
            }
        });
    }
}
