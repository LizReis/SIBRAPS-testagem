package com.pet.buscaativa.services.impl;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.services.exceptions.ValidationException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Painel administrativo local da opção B. Decisão não equivale à aplicação. */
@Service
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
@Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE)
public class PainelPendenciasLocalEnsaioService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final EntityManager em;
    private final OrigemHistoricaEfetivaLocal origemEfetiva;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public enum Estado { PENDENTE, APROVADA, REJEITADA }
    public enum Aplicacao { SEM_APLICACAO, RESOLUCAO_REGISTRADA, REVERTIDA, INCONCLUSIVA }
    private record SituacaoAplicacao(Aplicacao estado,UUID resolucao) {}
    public enum AplicacaoOrigem { SEM_APLICACAO, ORIGEM_RECONHECIDA, INCONCLUSIVA, REVERTIDA }
    public record Item(UUID ticket,UUID paciente,UUID vinculo,String nome,PendenciaRevisaoProveniencia.TipoRegistro tipoRegistro,
            Instant solicitadoEm,Estado estado,UUID decisao,Instant decididaEm,
            Integer versaoVinculo, Long registro, String justificativaSolicitacao, String justificativaDecisao, Aplicacao aplicacao, UUID resolucaoAtiva,
            boolean revisaoOrigem, AplicacaoOrigem aplicacaoOrigem, UUID registroAplicacaoOrigem, UUID registroReversaoOrigem,
            UUID solicitacaoAnterior,UUID novaSolicitacao) {}
    public record Pagina(long pendentes,long aprovadas,long rejeitadas,java.util.List<Item> itens,long total,int pagina,int tamanho) {}

    public Pagina listar(Estado filtro,int pagina,int tamanho) {
        var contexto=atores.autorizar(true);
        if(pagina<0||tamanho<1||tamanho>100) throw new ValidationException("Paginação inválida.");
        // Junção composta explícita: nem ticket nem identidade global autorizam outra unidade.
        String from="""
                from PendenciaRevisaoProveniencia p join p.vinculo v join v.paciente pessoa
                left join DecisaoRevisaoProveniencia d on d.pendencia=p and d.pacienteId=p.pacienteId and d.unidadeChave=p.unidadeChave
                where p.unidadeChave=:unidade and v.unidadeChave=:unidade and pessoa.id=p.pacienteId
                """;
        String aberto=" and d.id is null",aprovado=" and d.decisao=:decisao",rejeitado=aprovado;
        long pendentes=contar(from+aberto,contexto.unidade(),null);
        long aprovadas=contar(from+aprovado,contexto.unidade(),DecisaoRevisaoProveniencia.Decisao.APROVAR);
        long rejeitadas=contar(from+rejeitado,contexto.unidade(),DecisaoRevisaoProveniencia.Decisao.REJEITAR);
        String condicao=filtro==null?"":filtro==Estado.PENDENTE?aberto:aprovado;
        var q=em.createQuery("select p,d "+from+condicao+" order by p.solicitadoEm desc,p.id desc",Object[].class)
                .setParameter("unidade",contexto.unidade());
        if(filtro==Estado.APROVADA||filtro==Estado.REJEITADA) q.setParameter("decisao",
                filtro==Estado.APROVADA?DecisaoRevisaoProveniencia.Decisao.APROVAR:DecisaoRevisaoProveniencia.Decisao.REJEITAR);
        long total=filtro==null?pendentes+aprovadas+rejeitadas:filtro==Estado.PENDENTE?pendentes:filtro==Estado.APROVADA?aprovadas:rejeitadas;
        boolean instalada=com.pet.buscaativa.migration.AplicacaoPendenciaSchemaGuard.instalada(jdbc);
        var origens=new java.util.HashMap<Long,java.util.Map<UUID,OrigemHistoricaEfetivaLocal.Reconhecimento>>();
        boolean reabertura=com.pet.buscaativa.migration.ReaberturaOrigemSchemaGuard.instalada(jdbc);
        long offset=(long)pagina*tamanho;
        var itens=offset>=total?java.util.List.<Item>of():q.setFirstResult(Math.toIntExact(offset)).setMaxResults(tamanho)
                .getResultList().stream().map(row->{
                    var p=(PendenciaRevisaoProveniencia)row[0];var d=(DecisaoRevisaoProveniencia)row[1];
                    var v=p.getVinculo();
                    var aplicacao=instalada?situacaoAplicacao(p):new SituacaoAplicacao(Aplicacao.SEM_APLICACAO,null);
                    boolean revisaoOrigem=p.getSituacaoEvidencia()==com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal.Situacao.REVISAR_ORIGEM_DESCONHECIDA;
                    var origem=revisaoOrigem?origens.computeIfAbsent(v.getId(),id->origemEfetiva.consultarPorTicket(v)).get(p.getId()):null;
                    UUID anterior=null,nova=null;
                    if(reabertura && revisaoOrigem) {
                        anterior=relacaoOrigem(p,false);
                        nova=relacaoOrigem(p,true);
                    }
                    return new Item(p.getId(),v.getPaciente().getIdPublico(),v.getIdPublico(),v.getPaciente().getNome(),
                            p.getTipoRegistro(),p.getSolicitadoEm(),d==null?Estado.PENDENTE:
                            d.getDecisao()==DecisaoRevisaoProveniencia.Decisao.APROVAR?Estado.APROVADA:Estado.REJEITADA,
                            d==null?null:d.getId(),d==null?null:d.getDecididaEm(), v.getVersion(),
                            p.getRegistroId(), p.getJustificativa(), d==null?null:d.getJustificativa(), aplicacao.estado(),aplicacao.resolucao(),revisaoOrigem,
                            origem==null?AplicacaoOrigem.SEM_APLICACAO:origem.reversao()!=null?AplicacaoOrigem.REVERTIDA:
                            origem.comprovada()?AplicacaoOrigem.ORIGEM_RECONHECIDA:AplicacaoOrigem.INCONCLUSIVA,
                            origem==null?null:origem.id(),origem==null?null:origem.reversao(),anterior,nova);
                }).toList();
        return new Pagina(pendentes,aprovadas,rejeitadas,itens,total,pagina,tamanho);
    }

    private UUID relacaoOrigem(PendenciaRevisaoProveniencia p,boolean sucessor) {
        String selecionada=sucessor?"novo_id":"anterior_id",filtro=sucessor?"anterior_id":"novo_id";
        return jdbc.queryForList("select "+selecionada+" from tb_reabertura_origem_historica where "+filtro+
                "=? and vinculo_id=? and paciente_id=? and unidade_chave=? and historico_id=?",
                UUID.class,p.getId(),p.getVinculo().getId(),p.getPacienteId(),p.getUnidadeChave(),p.getRegistroId())
                .stream().findFirst().orElse(null);
    }

    private SituacaoAplicacao situacaoAplicacao(PendenciaRevisaoProveniencia p) {
        var ativas=jdbc.queryForList("""
                select resolucao_id from vw_aplicacao_pendencia_ativa
                where pendencia_id=? and vinculo_id=? and paciente_id=? and unidade_chave=?
                """,UUID.class,p.getId(),p.getVinculo().getId(),p.getPacienteId(),p.getUnidadeChave());
        if(ativas.size()>1) return new SituacaoAplicacao(Aplicacao.INCONCLUSIVA,null);
        if(ativas.size()==1) return new SituacaoAplicacao(Aplicacao.RESOLUCAO_REGISTRADA,ativas.getFirst());
        boolean anterior=Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tb_aplicacao_pendencia_resolucao
                    where pendencia_id=? and vinculo_id=? and paciente_id=? and unidade_chave=?)
                """,Boolean.class,p.getId(),p.getVinculo().getId(),p.getPacienteId(),p.getUnidadeChave()));
        return new SituacaoAplicacao(anterior?Aplicacao.REVERTIDA:Aplicacao.SEM_APLICACAO,null);
    }

    private long contar(String from,String unidade,DecisaoRevisaoProveniencia.Decisao decisao) {
        var q=em.createQuery("select count(p) "+from,Long.class).setParameter("unidade",unidade);
        if(decisao!=null)q.setParameter("decisao",decisao);
        return q.getSingleResult();
    }
}
