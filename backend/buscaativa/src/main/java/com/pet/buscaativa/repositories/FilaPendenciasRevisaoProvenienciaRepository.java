package com.pet.buscaativa.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado;

/**
 * Consulta interna exclusiva da fila de pendencias de UMA unidade.
 * Sem findAll, busca por ticket sem escopo, escrita ou operacoes de decisao.
 * A chave da unidade deve vir do servico autenticado, nunca de parametros HTTP.
 */
@Transactional(readOnly = true)
public interface FilaPendenciasRevisaoProvenienciaRepository
        extends Repository<PendenciaRevisaoProveniencia, UUID> {

    Page<PendenciaRevisaoProveniencia> findByUnidadeChaveAndEstadoOrderBySolicitadoEmDesc(
            String unidadeChave, Estado estado, Pageable pagina);

    /** Detalhe apenas da propria unidade e somente enquanto PENDENTE. */
    Optional<PendenciaRevisaoProveniencia> findByIdAndUnidadeChaveAndEstado(
            UUID id, String unidadeChave, Estado estado);
}
