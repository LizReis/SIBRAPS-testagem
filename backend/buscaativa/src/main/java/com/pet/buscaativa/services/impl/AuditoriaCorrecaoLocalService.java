package com.pet.buscaativa.services.impl;

import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.AuditoriaCorrecaoDTO;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Leitura operacional da trilha append-only. Não atribui origem a registros antigos. */
@Service
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
@Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE)
public class AuditoriaCorrecaoLocalService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final EscopoUnidadeService escopo;
    private final EntityManager em;

    public record Pagina(List<AuditoriaCorrecaoDTO> content,long totalElements,int totalPages,int number,int size) {}

    public Pagina individual(Long id,int pagina,int tamanho) {
        var contexto=atores.autorizar(false);
        var a=em.createQuery("select a from Agendamento a where a.id=:id and a.unidadeOrigemChave=:unidade",Agendamento.class)
                .setParameter("id",id).setParameter("unidade",contexto.unidade()).getResultList().stream().findFirst()
                .orElseThrow(AuditoriaCorrecaoLocalService::naoEncontrado);
        autorizacao.validarAgendamentoLocal(a);
        exigirVinculo(a.getPaciente());
        if(a.getUsuario()==null || a.getUsuario().getId()==null) throw naoEncontrado();
        exigirResponsavel(contexto,a.getUsuario());
        // Origem canônica já conferida acima. O rótulo da trilha é uma segunda
        // restrição, nunca a fonte de autorização de unidade.
        return listar("""
                from AuditoriaCorrecaoFrequencia r
                where r.agendamento.id=:id and r.unidade=:rotulo and r.responsavel.id=:responsavel
                and r.paciente.id=r.agendamento.paciente.id and r.tipoCorrecao='INDIVIDUAL'
                and r.sessao is null and r.participante is null
                """,id,a.getUsuario().getId(),pagina,tamanho);
    }

    public Pagina coletiva(Long id,int pagina,int tamanho) {
        var contexto=atores.autorizar(false);
        var s=em.createQuery("""
                select s from SessaoGrupo s where s.id=:id and s.unidadeOrigemChave=:unidade
                and s.grupo.unidadeOrigemChave=:unidade
                """,SessaoGrupo.class).setParameter("id",id).setParameter("unidade",contexto.unidade())
                .getResultList().stream().findFirst().orElseThrow(AuditoriaCorrecaoLocalService::naoEncontrado);
        if(s.getStatus()!=StatusSessaoGrupo.REALIZADA ||
                AvaliacaoProvenienciaClinica.origemSessao(s)!=AvaliacaoProvenienciaClinica.Situacao.COMPROVADA)
            throw naoEncontrado();
        // Mesma barreira de composição da leitura de sessão operacional.
        for(var p:s.getParticipantes()) {
            autorizacao.validarSessaoLocal(s,p.getPaciente());
            exigirVinculo(p.getPaciente());
        }
        var responsavel=s.getResponsavelAtendimento();
        exigirResponsavel(contexto,responsavel);
        return listar("""
                from AuditoriaCorrecaoFrequencia r
                where r.sessao.id=:id and r.unidade=:rotulo and r.responsavel.id=:responsavel
                and r.tipoCorrecao='GRUPO' and r.agendamento is null
                and r.participante.sessaoGrupo.id=r.sessao.id and r.participante.paciente.id=r.paciente.id
                """,id,responsavel.getId(),pagina,tamanho);
    }

    private void exigirVinculo(Paciente paciente) {
        if(paciente==null || paciente.getIdPublico()==null) throw naoEncontrado();
        var v=autorizacao.exigirVinculoLocal(paciente.getIdPublico());
        if(!ProjecaoClinicaVinculoService.chaveCanonica(v)) throw naoEncontrado();
    }
    private static void exigirResponsavel(AtoresEstadoClinicoLocalEnsaio.Contexto contexto,Usuario responsavel) {
        if(responsavel==null || responsavel.getId()==null || responsavel.getIdPublico()==null) throw naoEncontrado();
        if(contexto.perfil()!=TipoUsuario.ADMINISTRADOR && !Objects.equals(contexto.ator(),responsavel.getIdPublico()))
            throw new AccessDeniedException("Perfil sem autorização para consultar a auditoria deste atendimento.");
    }
    private Pagina listar(String jpql,Long id,Long responsavel,int pagina,int tamanho) {
        if(pagina<0 || tamanho<1 || tamanho>100) throw new ValidationException("Paginação inválida.");
        String rotulo=escopo.unidadePainel().valor();
        long total=em.createQuery("select count(r) "+jpql,Long.class)
                .setParameter("id",id).setParameter("rotulo",rotulo).setParameter("responsavel",responsavel).getSingleResult();
        long offset=(long)pagina*tamanho;
        var itens=offset>=total ? List.<AuditoriaCorrecaoDTO>of() : em.createQuery(
                "select r "+jpql+" order by r.ocorridoEm desc,r.versaoResultante desc,r.id desc",AuditoriaCorrecaoFrequencia.class)
                .setParameter("id",id).setParameter("rotulo",rotulo).setParameter("responsavel",responsavel)
                .setFirstResult(Math.toIntExact(offset)).setMaxResults(tamanho).getResultList().stream().map(AuditoriaCorrecaoDTO::new).toList();
        return new Pagina(itens,total,Math.toIntExact((total+tamanho-1)/tamanho),pagina,tamanho);
    }
    private static ResourceNotFoundException naoEncontrado() {return new ResourceNotFoundException("Registro não encontrado.");}
}
