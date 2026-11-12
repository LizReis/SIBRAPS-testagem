package com.pet.buscaativa.repositories;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.data.domain.*;
import com.pet.buscaativa.entities.AuditoriaRegularizacaoCancelamento;
/** No save/update/delete surface. Writes belong to the transactional audit recorder. */
public interface AuditoriaRegularizacaoRepository extends Repository<AuditoriaRegularizacaoCancelamento,UUID> {
    Page<AuditoriaRegularizacaoCancelamento> findByAgendamento_IdAndUnidade(Long id,String unidade,Pageable pagina);
    long countByAgendamento_IdAndTipoRegularizacao(Long id,String tipo);
    long count();
}
