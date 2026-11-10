package com.pet.buscaativa.services;

import java.time.Clock;
import java.text.Normalizer;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.*;
import org.springframework.data.domain.*;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;
import com.pet.buscaativa.services.exceptions.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Service @RequiredArgsConstructor
public class AuditoriaBuscaAtivaService {
    private final EntityManager entityManager;
    private final Clock clock;
    private final EscopoUnidadeService scope;
    private final UsuarioRepository users;
    private final PacienteRepository patients;
    private final AuditoriaBuscaAtivaRepository events;
    public record Resultado(AuditoriaBuscaAtivaDTO evento,boolean criado) {}

    @Transactional(propagation=Propagation.MANDATORY)
    public void registrarConsultaLista(int quantidade) {
        var actor=autor(false);var event=new AuditoriaBuscaAtiva(null,actor,scope.unidadePainel().valor(),TipoEventoBuscaAtiva.CONSULTA_LISTA_BUSCA_ATIVA,
                UUID.randomUUID(),clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),"LISTA_DA_UNIDADE",quantidade,null,null);
        entityManager.persist(event);entityManager.flush();
    }
    @Transactional
    public Resultado registrarVisualizacao(UUID patient,VisualizacaoAlertaDTO dto) {
        if(dto==null || dto.idRequisicao()==null)throw invalid();
        return registrar(patient,dto.idRequisicao(),TipoEventoBuscaAtiva.VISUALIZACAO_ALERTA,null,null);
    }
    @Transactional
    public Resultado registrarEncaminhamento(UUID patient,EncaminhamentoAlertaDTO dto) {
        if(dto==null || dto.idRequisicao()==null)throw invalid();
        return registrar(patient,dto.idRequisicao(),TipoEventoBuscaAtiva.ENCAMINHAMENTO_ALERTA,texto(dto.destino(),1,200),texto(dto.descricao(),10,1000));
    }
    private Resultado registrar(UUID patientId,UUID requestKey,TipoEventoBuscaAtiva kind,String destination,String description) {
        var actor=autor(kind==TipoEventoBuscaAtiva.ENCAMINHAMENTO_ALERTA);
        var patient=patients.findByIdPublicoForUpdate(patientId).orElseThrow(()->new ResourceNotFoundException("Paciente não encontrado."));scope.validarAcesso(patient);
        // Same patient -> authenticated actor order as the agenda's patient -> user locks.
        actor=users.findByIdPublicoForUpdate(actor.getIdPublico()).orElseThrow(()->new AccessDeniedException("Usuário autenticado não identificado."));
        scope.validarUsuario(actor);validarPerfil(actor,kind==TipoEventoBuscaAtiva.ENCAMINHAMENTO_ALERTA);
        String unit=scope.unidadePainel().valor();var existing=events.findByAtor_IdAndChaveRequisicao(actor.getId(),requestKey);
        if(existing.isPresent()) {
            var e=existing.get();
            if(e.getTipoEvento()!=kind || e.getPaciente()==null || !Objects.equals(e.getPaciente().getId(),patient.getId()) || !e.getUnidade().equals(unit)
                    || !Objects.equals(e.getDestino(),destination) || !Objects.equals(e.getDescricao(),description))
                throw new ConflictException("Identificador de requisição já utilizado em outra operação.");
            return new Resultado(new AuditoriaBuscaAtivaDTO(e),false);
        }
        String situation=situacaoAtual(patient);
        if(kind==TipoEventoBuscaAtiva.ENCAMINHAMENTO_ALERTA && Boolean.TRUE.equals(patient.getIndicadoresPendentes()))
            throw new ConflictException("Revise os indicadores provisórios antes de registrar um encaminhamento deste alerta.");
        var e=new AuditoriaBuscaAtiva(patient,actor,unit,kind,requestKey,clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),situation,1,destination,description);
        entityManager.persist(e);entityManager.flush();return new Resultado(new AuditoriaBuscaAtivaDTO(e),true);
    }
    private String situacaoAtual(Paciente p) {
        if(p.getStatusPaciente()!=StatusPaciente.ATIVO)throw new ConflictException("Paciente sem situação ativa para esta operação.");
        if(Boolean.TRUE.equals(p.getIndicadoresPendentes()))return "REVISAO_HISTORICO";
        if(Boolean.TRUE.equals(p.getGatilhoVisitaAcionado()))return p.isSituacaoRua()?"BUSCA_PONTO_REFERENCIA":"VISITA_DOMICILIAR";
        if(p.getClassificacaoRisco()==ClassificacaoRisco.VERMELHO)return "RISCO_VERMELHO";
        throw new ConflictException("Não existe situação atual de busca ativa confirmada para esta operação.");
    }
    @Transactional(readOnly=true)
    public Page<AuditoriaBuscaAtivaDTO> listarPaciente(UUID id,int page,int size) {
        var actor=autor(true);var patient=patients.findByIdPublico(id).orElseThrow(()->new ResourceNotFoundException("Paciente não encontrado."));scope.validarAcesso(patient);
        return events.findByPaciente_IdAndUnidade(patient.getId(),scope.unidadePainel().valor(),pagina(page,size)).map(AuditoriaBuscaAtivaDTO::new);
    }
    @Transactional(readOnly=true)
    public Page<AuditoriaBuscaAtivaDTO> listarUnidade(int page,int size) {
        var actor=autor(false);if(actor.getTipoUsuario()!=TipoUsuario.ADMINISTRADOR)throw new AccessDeniedException("Consulta restrita à administração da unidade.");
        Long usf=actor.getUnidadeAtuacao()==UnidadeAtuacao.USF?actor.getUsfAtuacao().getId():null;
        CapsEnum caps=usf==null?CapsEnum.valueOf(actor.getUnidadeAtuacao().name()):null;
        return events.findUnidadeAtual(scope.unidadePainel().valor(),usf,caps,pagina(page,size)).map(AuditoriaBuscaAtivaDTO::new);
    }
    private Usuario autor(boolean clinicalRole) {scope.validarUnidadeConfigurada();var actor=scope.usuarioLogado();validarPerfil(actor,clinicalRole);return actor;}
    private void validarPerfil(Usuario actor,boolean clinicalRole) {
        if(actor.getTipoUsuario()==TipoUsuario.ADMINISTRADOR || actor.getTipoUsuario()==TipoUsuario.PROFISSIONAL
                || !clinicalRole && actor.getTipoUsuario()==TipoUsuario.RECEPCAO)return;
        throw new AccessDeniedException("Usuário sem autorização para esta operação de alerta.");
    }
    private String texto(String raw,int min,int max) {
        if(raw==null || raw.length()>max)throw invalid();String value=Normalizer.normalize(raw,Normalizer.Form.NFC).strip().replaceAll("[\\p{Z}\\s]+"," ");
        if(value.length()<min || value.codePoints().anyMatch(Character::isISOControl))throw invalid();return value;
    }
    private ValidationException invalid(){return new ValidationException("Informe identificador de requisição e dados operacionais válidos, dentro dos limites do contrato.");}
    private Pageable pagina(int page,int size) {
        if(page<0 || size<1 || size>100)throw new ValidationException("Informe página não negativa e tamanho entre 1 e 100.");
        return PageRequest.of(page,size,Sort.by(Sort.Order.desc("ocorridoEm"),Sort.Order.desc("id")));
    }
}
