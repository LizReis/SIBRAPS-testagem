package com.pet.buscaativa.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.pet.buscaativa.entities.UsfReferencia;

import java.util.List;

@Repository
public interface UsfReferenciaRepository extends JpaRepository<UsfReferencia, Long> {

    List<UsfReferencia> findAllByOrderByNomeUsfAsc();

}
