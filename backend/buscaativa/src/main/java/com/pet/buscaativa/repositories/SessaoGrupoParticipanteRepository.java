package com.pet.buscaativa.repositories;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.SessaoGrupoParticipante;

@Repository
public interface SessaoGrupoParticipanteRepository extends JpaRepository<SessaoGrupoParticipante, Long> {

    Optional<SessaoGrupoParticipante> findBySessaoGrupoAndPaciente(SessaoGrupo sessaoGrupo, Paciente paciente);

    List<SessaoGrupoParticipante> findByPaciente(Paciente paciente);
}