package com.pet.buscaativa.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import com.pet.buscaativa.entities.Paciente;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.CapsEnum;

import jakarta.persistence.LockModeType;



@Repository
public interface PacienteRepository extends JpaRepository<Paciente, Long>, JpaSpecificationExecutor<Paciente>{

    boolean existsByProfissionalReferenciaId(Long usuarioId);
 
    Optional<Paciente> findByIdPublico(UUID idPublico);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Paciente p where p.idPublico = :idPublico")
    Optional<Paciente> findByIdPublicoForUpdate(UUID idPublico);

    Optional<Paciente> findByCns(String cns);

    Optional<Paciente> findByCpf(String cpf);

    Optional<Paciente> findByNome(String nome);

    Optional<Paciente> findByNomeMae(String nomeMae);

    List<Paciente> findByNomeContainingIgnoreCase (String nome);    

    List<Paciente> findByNomeIgnoreCaseAndNomeMaeIgnoreCaseAndDataNascimento(
    String nome, String nomeMae, LocalDate dataNascimento);

    List<Paciente> findByStatusPaciente(StatusPaciente statusPaciente);

    List<Paciente> findByStatusPacienteAndClassificacaoRisco(StatusPaciente statusPaciente, ClassificacaoRisco classificacaoRisco);

    @Query("""
            select p from Paciente p
            left join fetch p.profissionalReferencia pr
            where p.statusPaciente = :status
              and p.classificacaoRisco = :classificacao
              and (:dataInicio is null or p.dataUltimaPresenca >= :dataInicio)
              and (:dataFim is null or p.dataUltimaPresenca <= :dataFim)
              and (:profissionalId is null or pr.idPublico = :profissionalId)
              and (:tipoAcompanhamento is null or p.tipoAcompanhamento = :tipoAcompanhamento)
            order by p.nome
            """)
    List<Paciente> findParaRelatorioBuscaAtiva(
            @Param("status") StatusPaciente status,
            @Param("classificacao") ClassificacaoRisco classificacao,
            @Param("dataInicio") LocalDate dataInicio,
            @Param("dataFim") LocalDate dataFim,
            @Param("profissionalId") UUID profissionalId,
            @Param("tipoAcompanhamento") com.pet.buscaativa.entities.enums.TipoAcompanhamento tipoAcompanhamento);

    long countByStatusPaciente(StatusPaciente statusPaciente);

    long countByStatusPacienteAndClassificacaoRiscoIn(StatusPaciente statusPaciente, List<ClassificacaoRisco> classificacoes);

    @Query("""
            select p from Paciente p
            where p.statusPaciente = :statusPaciente
              and p.classificacaoRisco in :classificacoes
            order by case p.classificacaoRisco
                when com.pet.buscaativa.entities.enums.ClassificacaoRisco.VERMELHO then 0
                when com.pet.buscaativa.entities.enums.ClassificacaoRisco.AMARELO then 1
                else 2 end,
                p.countFaltas desc,
                p.nome asc
            """)
    List<Paciente> findPacientesPrioritarios(@Param("statusPaciente") StatusPaciente statusPaciente,
                                             @Param("classificacoes") List<ClassificacaoRisco> classificacoes,
                                             Pageable pageable);

    List<Paciente> findByStatusPacienteAndClassificacaoRiscoAndUsfReferencia_Id(
        StatusPaciente statusPaciente,
        ClassificacaoRisco classificacaoRisco,
        Long usfId);
        
    List<Paciente> findByStatusPacienteAndClassificacaoRiscoAndCapsReferencia(StatusPaciente statusPaciente, ClassificacaoRisco classificacaoRisco,
        CapsEnum capsReferencia);
}
