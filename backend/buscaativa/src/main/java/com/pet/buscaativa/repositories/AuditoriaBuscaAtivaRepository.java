package com.pet.buscaativa.repositories;
import java.util.*;
import org.springframework.data.repository.Repository;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.pet.buscaativa.entities.AuditoriaBuscaAtiva;
import com.pet.buscaativa.entities.enums.CapsEnum;

/** Insert-only storage is in the transactional service; no mutation repository methods. */
public interface AuditoriaBuscaAtivaRepository extends Repository<AuditoriaBuscaAtiva,UUID> {
    Optional<AuditoriaBuscaAtiva> findByAtor_IdAndChaveRequisicao(Long actor,UUID key);
    Page<AuditoriaBuscaAtiva> findByPaciente_IdAndUnidade(Long patient,String unidade,Pageable page);
    @Query("select e from AuditoriaBuscaAtiva e left join e.paciente p left join p.usfReferencia u where e.unidade=:unidade and (p is null or (:usf is not null and u.id=:usf) or (:caps is not null and p.capsReferencia=:caps))")
    Page<AuditoriaBuscaAtiva> findUnidadeAtual(@Param("unidade")String unit,@Param("usf")Long usf,@Param("caps")CapsEnum caps,Pageable page);
    long count();
}
