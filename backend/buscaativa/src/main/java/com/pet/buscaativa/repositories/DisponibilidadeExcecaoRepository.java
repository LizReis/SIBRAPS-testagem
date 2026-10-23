package com.pet.buscaativa.repositories;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.pet.buscaativa.entities.DisponibilidadeExcecao;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.TurnoEnum;

@Repository
public interface DisponibilidadeExcecaoRepository extends JpaRepository<DisponibilidadeExcecao, Long> {

    Optional<DisponibilidadeExcecao> findByUsuarioAndDataAndTurno(Usuario usuario, LocalDate data, TurnoEnum turno);

    List<DisponibilidadeExcecao> findByUsuario(Usuario usuario);

    List<DisponibilidadeExcecao> findByUsuarioAndDataBetween(Usuario usuario, LocalDate dataInicio, LocalDate dataFim);
}