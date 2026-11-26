package com.pet.buscaativa.services.impl;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.pet.buscaativa.migration.AplicacaoPendenciaSchemaGuard;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;

/** Associação explícita, nunca inferida a partir de decisão genérica ou horário. */
@Component
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
class AplicacaoPendenciaResolucaoLocal {
    private final JdbcTemplate jdbc;
    private final HistoricoFrequenciaLocalEnsaio historico;

    private record Ticket(String tipo,Long registro,UUID decisao,String resultado,String situacao) {}

    UUID conferir(VinculoAssistencial local,LinhaDoTempoFrequenciasVinculoService.Evidencias bruto,
            PlanoResolucaoFrequencia.Referencia desconsiderado,UUID ticket) {
        if(!AplicacaoPendenciaSchemaGuard.instalada(jdbc))
            throw new ConflictException("Estrutura de aplicação de pendência não instalada.");
        var p=jdbc.query("""
                select p.tipo_registro,p.registro_id,d.id,d.decisao,p.situacao_evidencia from tb_pendencia_revisao_proveniencia p
                left join tb_decisao_revisao_proveniencia d on d.pendencia_id=p.id
                    and d.paciente_id=p.paciente_id and d.unidade_chave=p.unidade_chave
                where p.id=? and p.vinculo_id=? and p.paciente_id=? and p.unidade_chave=?
                """,(rs,n)->new Ticket(rs.getString(1),rs.getLong(2),rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5)),
                ticket,local.getId(),local.getPaciente().getId(),local.getUnidadeChave()).stream().findFirst()
                .orElseThrow(()->new ResourceNotFoundException("Pendência não encontrada."));
        if(!"COMPATIVEL_SOMENTE_PARA_CONFERENCIA".equals(p.situacao()))
            throw new ConflictException("Pendência de origem não autoriza resolução de duplicidade.");
        if(!"APROVAR".equals(p.resultado()) || p.decisao()==null)
            throw new ConflictException("Pendência de revisão sem aprovação administrativa.");
        boolean corresponde=switch(desconsiderado.fonte()) {
            case ATENDIMENTO_INDIVIDUAL -> {
                var a=bruto.agenda().stream().filter(f->desconsiderado.id().equals(f.getId())).findFirst().orElseThrow();
                if("AGENDAMENTO".equals(p.tipo())) yield p.registro().equals(a.getId());
                if(!"HISTORICO".equals(p.tipo())) yield false;
                var h=historico.individual(a,local,a.getFrequenciaAssistencial());
                yield h!=null && p.registro().equals(h.getId());
            }
            case SESSAO_GRUPO -> {
                var f=bruto.participacoes().stream().filter(x->desconsiderado.id().equals(x.getId())).findFirst().orElseThrow();
                if("SESSAO_GRUPO".equals(p.tipo())) yield p.registro().equals(f.getSessaoGrupo().getId());
                if(!"HISTORICO".equals(p.tipo())) yield false;
                var h=historico.coletivo(f,local,FrequenciaAssistencial.valueOf(f.getStatusPresenca().name()));
                yield h!=null && p.registro().equals(h.getId());
            }
        };
        if(!corresponde) throw new ConflictException("A pendência não corresponde ao fato desconsiderado.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from vw_aplicacao_pendencia_ativa where pendencia_id=?)
                """,Boolean.class,ticket))) throw new ConflictException("Pendência já possui aplicação ativa.");
        return p.decisao();
    }

    void registrar(VinculoAssistencial local,UUID resolucao,UUID ticket,UUID decisao) {
        jdbc.update("""
                insert into tb_aplicacao_pendencia_resolucao
                    (resolucao_id,pendencia_id,decisao_id,vinculo_id,paciente_id,unidade_chave,decisao)
                values (?,?,?,?,?,?,'APROVAR')
                """,resolucao,ticket,decisao,local.getId(),local.getPaciente().getId(),local.getUnidadeChave());
    }
}
