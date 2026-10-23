package com.pet.buscaativa.repositories;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.pet.buscaativa.entities.BloqueioAgenda;
import com.pet.buscaativa.entities.Usuario;


@Repository
public interface BloqueioAgendaRepository extends JpaRepository<BloqueioAgenda, Long>{

    @Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END FROM BloqueioAgenda b WHERE b.usuario = :usuario AND :data BETWEEN b.dataInicio AND b.dataFim")
    boolean isDataBloqueadaParaUsuario(@Param("usuario") Usuario usuario, @Param("data") LocalDate data);

    List<BloqueioAgenda> findByUsuario(Usuario usuario);
}
