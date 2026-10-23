package com.pet.buscaativa.repositories;

import java.util.List; 
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.pet.buscaativa.entities.*;

@Repository
public interface InscricaoRetroativaGrupoAuditoriaRepository extends JpaRepository<InscricaoRetroativaGrupoAuditoria,Long> {
 
    List<InscricaoRetroativaGrupoAuditoria> findByGrupoOrderByCreatedAtDesc(GrupoTerapeutico grupo);
}