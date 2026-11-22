package com.pet.buscaativa.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.DecisaoRevisaoProveniencia;

/**
 * Contrato interno e somente de LEITURA dos eventos V13.
 * Nenhum acesso global, escrita, alteracao ou exclusao.
 * O chamador deve conferir autorizacao e derivar paciente/unidade do
 * vinculo local autenticado; o UUID nao autoriza consulta por si so.
 */
@Transactional(readOnly = true)
public interface DecisaoRevisaoProvenienciaRepository
        extends Repository<DecisaoRevisaoProveniencia, UUID> {

    Optional<DecisaoRevisaoProveniencia> findByIdAndPacienteIdAndUnidadeChave(
            UUID id, Long pacienteId, String unidadeChave);

    Optional<DecisaoRevisaoProveniencia> findByPendenciaIdAndPacienteIdAndUnidadeChave(
            UUID pendenciaId, Long pacienteId, String unidadeChave);
}
