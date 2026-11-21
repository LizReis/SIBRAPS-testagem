package com.pet.buscaativa.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.pet.buscaativa.entities.VinculoAssistencial;

/**
 * Consultas internas de modelagem. Nao substituem as verificacoes de
 * autorizacao e escopo; nao expor diretamente por controlador.
 */
@Repository
public interface VinculoAssistencialRepository extends JpaRepository<VinculoAssistencial, Long> {
    Optional<VinculoAssistencial> findByPacienteIdAndUnidadeChave(Long pacienteId, String unidadeChave);
    Optional<VinculoAssistencial> findByIdPublico(UUID idPublico);
    List<VinculoAssistencial> findByPacienteId(Long pacienteId);
    boolean existsByPacienteIdAndUnidadeChave(Long pacienteId, String unidadeChave);
}
