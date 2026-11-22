package com.pet.buscaativa.repositories;

import java.util.UUID;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.DecisaoRevisaoProveniencia;

/**
 * Contrato interno restrito a INSERT de decisao V13 e teste de duplicidade.
 * Sem update, delete, findAll ou busca por identificador sem escopo.
 * Unicidade fisica da pendencia tambem e garantida no banco (V13).
 */
public interface DecisaoRevisaoProvenienciaComandoRepository
        extends Repository<DecisaoRevisaoProveniencia, UUID> {

    @Transactional
    <S extends DecisaoRevisaoProveniencia> S save(S decisao);

    @Transactional(readOnly = true)
    boolean existsByPendenciaIdAndPacienteIdAndUnidadeChave(
            UUID pendenciaId, Long pacienteId, String unidadeChave);
}
