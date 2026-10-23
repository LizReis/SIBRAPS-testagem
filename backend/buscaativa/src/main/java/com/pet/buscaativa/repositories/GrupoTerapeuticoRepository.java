package com.pet.buscaativa.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.pet.buscaativa.entities.GrupoTerapeutico;
import com.pet.buscaativa.entities.Usuario;

@Repository
public interface GrupoTerapeuticoRepository extends JpaRepository<GrupoTerapeutico, Long> {

    List<GrupoTerapeutico> findByAtivoTrue();

    List<GrupoTerapeutico> findByCoordenador(Usuario coordenador);
}