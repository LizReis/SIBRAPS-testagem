package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.text.Normalizer;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.data.domain.*;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.entities.dto.AuditoriaBuscaAtivaDTO;
import com.pet.buscaativa.repositories.AuditoriaBuscaAtivaRepository;
import com.pet.buscaativa.services.exceptions.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** RNF17 local, sem alterar trilha legada, sem encaminhar dados a serviço externo. */
@Service
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
public class AuditoriaBuscaLocalEnsaioService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final CoordenacaoEscritaClinicaEnsaio coordenacao;
    private final AgendaAssistencialLocks locks;
    private final CalculoIndicadoresVinculo calculo;
    private final EvidenciaStatusVinculo evidencia;
    private final BuscaAtivaPainelLocalEnsaioService busca;
    private final AuditoriaBuscaAtivaRepository eventos;
    private final EntityManager em;
    private final Clock clock;

    public record Resultado(AuditoriaBuscaAtivaDTO evento,boolean criado) {}

    @Transactional
    public BuscaAtivaPainelLocalEnsaioService.Busca consultarLista() {
        return consultarLista(UUID.randomUUID());
    }

    /** A chave deduplica o evento. Uma repetição lê a lista atual, sem armazenar seu conteúdo. */
    @Transactional
    public BuscaAtivaPainelLocalEnsaioService.Busca consultarLista(UUID requisicao) {
        if (requisicao == null) throw new ValidationException("Identificador da requisição obrigatório.");
        var contexto = atores.autorizarOperacional();
        var ator = locks.travarUsuario(contexto.ator());
        atores.validar(contexto, null, Map.of(contexto.ator(), ator));
        var tipo = TipoEventoBuscaAtiva.CONSULTA_LISTA_BUSCA_ATIVA;
        var existente = eventos.findByAtor_IdAndChaveRequisicao(ator.getId(), requisicao);
        if (existente.isPresent()) {
            var e = existente.orElseThrow();
            if (e.getTipoEvento() != tipo || e.getPaciente() != null
                    || !("VINCULO:" + contexto.unidade()).equals(e.getUnidade()))
                throw new ConflictException("Identificador de requisição já utilizado em outra operação.");
            return busca.buscaAtivaSnapshot();
        }
        var dados = busca.buscaAtivaSnapshot();
        em.persist(AuditoriaBuscaAtiva.local(null, ator, contexto.unidade(), tipo, requisicao,
                clock.instant().truncatedTo(ChronoUnit.MICROS), "LISTA_DA_UNIDADE",
                dados.alertas().size(), null, null, null, false, false));
        em.flush();
        return dados;
    }

    @Transactional
    public Resultado visualizar(UUID paciente,UUID vinculo,Integer versao,UUID requisicao) {
        return registrar(paciente,vinculo,versao,requisicao,TipoEventoBuscaAtiva.VISUALIZACAO_ALERTA,null,null);
    }
    @Transactional
    public Resultado encaminhar(UUID paciente,UUID vinculo,Integer versao,UUID requisicao,String destino,String descricao) {
        return registrar(paciente,vinculo,versao,requisicao,TipoEventoBuscaAtiva.ENCAMINHAMENTO_ALERTA,
                texto(destino,1,200),texto(descricao,10,1000));
    }

    private Resultado registrar(UUID paciente,UUID vinculo,Integer versao,UUID requisicao,TipoEventoBuscaAtiva tipo,String destino,String descricao) {
        if(requisicao==null)throw new ValidationException("Identificador da requisição obrigatório.");
        boolean encaminhar=tipo==TipoEventoBuscaAtiva.ENCAMINHAMENTO_ALERTA;
        var contexto=encaminhar?atores.autorizar(false):atores.autorizarOperacional();
        var recursos=coordenacao.paraVinculo(contexto,paciente,vinculo,null);
        var local=recursos.vinculos().get(paciente);var ator=recursos.atores().ator();
        var existente=eventos.findByAtor_IdAndChaveRequisicao(ator.getId(),requisicao);
        if(existente.isPresent()) {
            var e=existente.get();
            if(e.getTipoEvento()!=tipo||e.getPaciente()==null||!e.getPaciente().getId().equals(local.getPaciente().getId())
                    ||!("VINCULO:"+contexto.unidade()).equals(e.getUnidade())
                    ||!Objects.equals(destino,e.getDestino())||!Objects.equals(descricao,e.getDescricao()))
                throw new ConflictException("Identificador de requisição já utilizado em outra operação.");
            return new Resultado(new AuditoriaBuscaAtivaDTO(e),false);
        }
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao,local.getVersion());
        var c=calculo.calcular(local,LocalDate.now(clock),evidencia.analisar(local),local.getIndicadoresPendentes());
        if(c.busca().decisao()!=ComparadorSelecaoBuscaAtivaVinculo.Decisao.INCLUIR)
            throw new ConflictException("Não existe situação atual de busca ativa confirmada.");
        boolean pendente=Boolean.TRUE.equals(local.getIndicadoresPendentes()),rf=Boolean.TRUE.equals(c.visita().valor().orElse(null));
        if(encaminhar&&pendente)throw new ConflictException("Revise os indicadores antes do encaminhamento.");
        String situacao=pendente?"REVISAO_HISTORICO":rf?(local.getPaciente().isSituacaoRua()?"BUSCA_PONTO_REFERENCIA":"VISITA_DOMICILIAR"):"RISCO_VERMELHO";
        var e=AuditoriaBuscaAtiva.local(local,ator,contexto.unidade(),tipo,requisicao,clock.instant().truncatedTo(ChronoUnit.MICROS),
                situacao,1,destino,descricao,c.risco().valor().orElse(null),rf,pendente);
        em.persist(e);em.flush();
        return new Resultado(new AuditoriaBuscaAtivaDTO(e),true);
    }

    @Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE)
    public Page<AuditoriaBuscaAtivaDTO> listarPaciente(UUID paciente,int pagina,int tamanho) {
        var contexto=atores.autorizar(false);
        var id=em.createQuery("select v.paciente.id from VinculoAssistencial v where v.paciente.idPublico=:paciente and v.unidadeChave=:unidade",Long.class)
                .setParameter("paciente",paciente).setParameter("unidade",contexto.unidade()).getResultList().stream().findFirst()
                .orElseThrow(()->new ResourceNotFoundException("Paciente não encontrado."));
        return eventos.findByPaciente_IdAndUnidade(id,"VINCULO:"+contexto.unidade(),pagina(pagina,tamanho)).map(AuditoriaBuscaAtivaDTO::new);
    }

    @Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE)
    public Page<AuditoriaBuscaAtivaDTO> listarUnidade(int pagina,int tamanho) {
        var contexto=atores.autorizar(true);
        return eventos.findUnidadeLocal("VINCULO:"+contexto.unidade(),contexto.unidade(),pagina(pagina,tamanho))
                .map(AuditoriaBuscaAtivaDTO::new);
    }

    private static Pageable pagina(int p,int t) {
        if(p<0||t<1||t>100)throw new ValidationException("Paginação inválida.");
        return PageRequest.of(p,t,Sort.by(Sort.Order.desc("ocorridoEm"),Sort.Order.desc("id")));
    }
    private static String texto(String raw,int min,int max) {
        if(raw==null||raw.length()>max)throw new ValidationException("Dados operacionais inválidos.");
        var s=Normalizer.normalize(raw,Normalizer.Form.NFC).strip().replaceAll("[\\p{Z}\\s]+"," ");
        if(s.length()<min||s.codePoints().anyMatch(Character::isISOControl))throw new ValidationException("Dados operacionais inválidos.");
        return s;
    }
}
