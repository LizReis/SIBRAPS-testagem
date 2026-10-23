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

    boolean existsByPacienteAndSessaoGrupo(com.pet.buscaativa.entities.Paciente paciente,
                                            com.pet.buscaativa.entities.SessaoGrupo sessaoGrupo);
    java.util.Optional<HistoricoPaciente> findByPacienteAndSessaoGrupo(Paciente paciente, SessaoGrupo sessaoGrupo);
    List<HistoricoPaciente> findByAgendamento(Agendamento agendamento);

    @Query("""
            select h from HistoricoPaciente h
            left join fetch h.profissional
            left join fetch h.agendamento
            where h.paciente.idPublico = :pacienteId
            order by h.ocorridoEm desc, h.id desc
            """)
    List<HistoricoPaciente> findByPacienteIdPublicoOrderByOcorridoEmDesc(@Param("pacienteId") UUID pacienteId);
}