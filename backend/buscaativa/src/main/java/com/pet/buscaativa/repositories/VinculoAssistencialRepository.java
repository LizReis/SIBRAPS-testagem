package com.pet.buscaativa.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
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
    Optional<VinculoAssistencial> findByIdPublicoAndPaciente_IdPublicoAndUnidadeChave(
            UUID vinculoPublico, UUID pacientePublico, String unidadeChave);
    /** Resolução e bloqueio no mesmo predicado de identidade e unidade. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select v from VinculoAssistencial v
            where v.idPublico = :vinculo and v.paciente.idPublico = :paciente
              and v.unidadeChave = :unidade
            """)
    Optional<VinculoAssistencial> findLocalForEstadoUpdate(@Param("vinculo") UUID vinculo,
            @Param("paciente") UUID paciente, @Param("unidade") String unidade);

    List<VinculoAssistencial> findByPacienteId(Long pacienteId);
    boolean existsByPacienteIdAndUnidadeChave(Long pacienteId, String unidadeChave);
    boolean existsByPacienteId(Long pacienteId);
    boolean existsByPacienteIdAndUnidadeChaveNot(Long pacienteId, String unidadeChave);
    /**
     * Serializa apenas solicitacoes da mesma identidade e unidade.
     * Nao modifica dados clinicos e deve ser chamado dentro de transacao.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VinculoAssistencial v where v.id = :id")
    Optional<VinculoAssistencial> findByIdForPendenciaUpdate(@Param("id") Long id);

}
