package com.pet.buscaativa.repositories;

import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.pet.buscaativa.entities.AuditoriaCorrecaoFrequencia;

/** Deliberately exposes no save, update or delete operation. */
public interface AuditoriaCorrecaoRepository extends Repository<AuditoriaCorrecaoFrequencia,UUID> {
    Page<AuditoriaCorrecaoFrequencia> findByAgendamento_IdAndUnidade(Long id,String unidade,Pageable pageable);
    Page<AuditoriaCorrecaoFrequencia> findBySessao_IdAndUnidade(Long id,String unidade,Pageable pageable);
    boolean existsByAgendamento_Id(Long id);
    long count();
}
