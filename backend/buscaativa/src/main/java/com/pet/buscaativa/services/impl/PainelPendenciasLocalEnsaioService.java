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
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
@Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE)
public class PainelPendenciasLocalEnsaioService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final EntityManager em;
    public enum Estado { PENDENTE, APROVADA, REJEITADA }
    public record Item(UUID ticket,UUID paciente,UUID vinculo,String nome,PendenciaRevisaoProveniencia.TipoRegistro tipoRegistro,
            Instant solicitadoEm,Estado estado,UUID decisao,Instant decididaEm,
            Integer versaoVinculo, Long registro, String justificativaSolicitacao, String justificativaDecisao) {}
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
        long offset=(long)pagina*tamanho;
        var itens=offset>=total?java.util.List.<Item>of():q.setFirstResult(Math.toIntExact(offset)).setMaxResults(tamanho)
                .getResultList().stream().map(row->{
                    var p=(PendenciaRevisaoProveniencia)row[0];var d=(DecisaoRevisaoProveniencia)row[1];
                    var v=p.getVinculo();
                    return new Item(p.getId(),v.getPaciente().getIdPublico(),v.getIdPublico(),v.getPaciente().getNome(),
                            p.getTipoRegistro(),p.getSolicitadoEm(),d==null?Estado.PENDENTE:
                            d.getDecisao()==DecisaoRevisaoProveniencia.Decisao.APROVAR?Estado.APROVADA:Estado.REJEITADA,
                            d==null?null:d.getId(),d==null?null:d.getDecididaEm(), v.getVersion(),
                            p.getRegistroId(), p.getJustificativa(), d==null?null:d.getJustificativa());
                }).toList();
        return new Pagina(pendentes,aprovadas,rejeitadas,itens,total,pagina,tamanho);
    }

    private long contar(String from,String unidade,DecisaoRevisaoProveniencia.Decisao decisao) {
        var q=em.createQuery("select count(p) "+from,Long.class).setParameter("unidade",unidade);
        if(decisao!=null)q.setParameter("decisao",decisao);
        return q.getSingleResult();
    }
}
