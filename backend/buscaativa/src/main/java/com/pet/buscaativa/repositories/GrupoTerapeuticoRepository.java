package com.pet.buscaativa.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.pet.buscaativa.entities.GrupoTerapeutico;
import com.pet.buscaativa.entities.Usuario;

@Repository
public interface GrupoTerapeuticoRepository extends JpaRepository<GrupoTerapeutico, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select g from GrupoTerapeutico g where g.id=:id")
    java.util.Optional<GrupoTerapeutico> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    List<GrupoTerapeutico> findByAtivoTrue();

    List<GrupoTerapeutico> findByCoordenador(Usuario coordenador);
}