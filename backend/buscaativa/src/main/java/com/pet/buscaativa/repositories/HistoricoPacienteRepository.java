package com.pet.buscaativa.repositories;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.SessaoGrupo;

@Repository
public interface HistoricoPacienteRepository extends JpaRepository<HistoricoPaciente, Long> {

    /** Candidatos internos: a origem gravada da fonte é só o filtro inicial.
     * O serviço administrativo confere identidade, cadeia e frequência antes de retornar. */
    @Query("""
            select h from HistoricoPaciente h
            left join h.agendamento a
            left join h.sessaoGrupo s
            where h.paciente.idPublico=:paciente
              and (h.unidadeOrigemChave is null or trim(h.unidadeOrigemChave)='')
              and ((a.unidadeOrigemChave=:unidade and s is null)
                   or (s.unidadeOrigemChave=:unidade and a is null))
            order by h.ocorridoEm desc,h.id desc
            """)
    List<HistoricoPaciente> findCandidatosOrigemLocal(@Param("paciente") UUID paciente,@Param("unidade") String unidade);

    boolean existsByPacienteAndSessaoGrupo(com.pet.buscaativa.entities.Paciente paciente,
                                            com.pet.buscaativa.entities.SessaoGrupo sessaoGrupo);
    java.util.Optional<HistoricoPaciente> findByPacienteAndSessaoGrupo(Paciente paciente, SessaoGrupo sessaoGrupo);
    List<HistoricoPaciente> findAllByPacienteAndSessaoGrupo(Paciente paciente, SessaoGrupo sessaoGrupo);
    List<HistoricoPaciente> findByAgendamento(Agendamento agendamento);

    @Query("""
            select h from HistoricoPaciente h
            left join fetch h.profissional
            left join fetch h.agendamento
            where h.paciente.idPublico = :pacienteId
            order by h.ocorridoEm desc, h.id desc
            """)
    List<HistoricoPaciente> findByPacienteIdPublicoOrderByOcorridoEmDesc(@Param("pacienteId") UUID pacienteId);

    /**
     * Nunca inferir proveniencia do CAPS/USF de referencia ou do profissional
     * atual. Buscar somente eventos marcados na criacao com a unidade local.
     * A coerencia com a fonte do fato ainda sera validada no servico.
     */
    @Query("""
            select h from HistoricoPaciente h
            left join fetch h.profissional
            left join fetch h.agendamento
            left join fetch h.sessaoGrupo
            where h.paciente.idPublico = :pacienteId
              and h.unidadeOrigemChave = :unidade
            order by h.ocorridoEm desc, h.id desc
            """)
    List<HistoricoPaciente> findLocaisComOrigemComprovada(
            @Param("pacienteId") UUID pacienteId, @Param("unidade") String unidade);
}