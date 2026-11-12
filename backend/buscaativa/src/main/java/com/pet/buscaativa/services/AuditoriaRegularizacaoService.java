package com.pet.buscaativa.services;

import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.data.domain.*;
import jakarta.persistence.EntityManager;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.AuditoriaRegularizacaoDTO;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;
import com.pet.buscaativa.services.exceptions.*;
import lombok.RequiredArgsConstructor;

@Service @RequiredArgsConstructor
public class AuditoriaRegularizacaoService {
    private final EntityManager entityManager;
    private final Clock clock;
    private final EscopoUnidadeService scope;
    private final AutorizacaoClinicaService authorization;
    private final AuditoriaRegularizacaoRepository records;
    private final AuditoriaCorrecaoRepository corrections;
    private final AgendamentoRepository appointments;
    @Transactional(propagation=Propagation.MANDATORY)
    public void registrar(Agendamento a,String tipo,String motivo,int versaoAnterior) {
        authorization.validarAgendamento(a,true);
        entityManager.persist(new AuditoriaRegularizacaoCancelamento(a,scope.usuarioLogado(),scope.unidadePainel().valor(),
            tipo,motivo,clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),versaoAnterior));
        entityManager.flush();
    }
    /** Called under the appointment locks. No current legacy state is certified without an audit chain. */
    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public boolean origemFactualConfiavel(Agendamento a) {
        authorization.validarAgendamento(a,true);
        if(records.countByAgendamento_IdAndTipoRegularizacao(a.getId(),"REGISTRO_FATO_ASSISTENCIAL")!=1)return false;
        var unit=scope.unidadePainel().valor();
        var origins=records.findByAgendamento_IdAndUnidade(a.getId(),unit,Pageable.unpaged()).stream()
            .filter(r->r.getTipoRegularizacao().equals("REGISTRO_FATO_ASSISTENCIAL")).toList();
        if(origins.size()!=1)return false;
        var r=origins.getFirst();
        if(!Objects.equals(r.getPaciente().getId(),a.getPaciente().getId())||!Objects.equals(r.getResponsavel().getId(),a.getUsuario().getId()))return false;
        int expected=r.getVersaoResultante();var frequency=r.getFrequenciaResultante();
        var chain=corrections.findByAgendamento_IdAndUnidade(a.getId(),unit,Pageable.unpaged(Sort.by("versaoResultante"))).getContent();
        for(var change:chain){
            if(change.getVersaoResultante()<=expected)continue;
            if(change.getVersaoAnterior()!=expected || change.getVersaoResultante()!=expected+1
                || change.getSituacaoReserva()!=SituacaoReserva.CANCELADA || change.getFrequenciaAnterior()!=frequency
                || !Objects.equals(change.getPaciente().getId(),a.getPaciente().getId())
                || !Objects.equals(change.getResponsavel().getId(),a.getUsuario().getId()))return false;
            expected=change.getVersaoResultante();frequency=change.getFrequenciaNova();
        }
        return expected==a.getVersion() && frequency==a.getFrequenciaAssistencial();
    }
    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public void validarAusenciaDeFatoAnterior(Agendamento a) {
        authorization.validarAgendamento(a,true);
        if(corrections.existsByAgendamento_Id(a.getId()) || records.countByAgendamento_IdAndTipoRegularizacao(a.getId(),"REGISTRO_FATO_ASSISTENCIAL")>0)
            throw new PendenciaFrequenciaException("ORIGEM_REGULARIZACAO_PENDENTE_RECONCILIACAO","ORIGEM_REGULARIZACAO",a.getId(),null);
    }
    @Transactional(readOnly=true)
    public Page<AuditoriaRegularizacaoDTO> listar(Long id,int page,int size) {
        var a=appointments.findById(id).orElseThrow(()->new ResourceNotFoundException("Atendimento não encontrado."));
        authorization.validarAgendamento(a,true);
        if(page<0||size<1||size>100)throw new ValidationException("Informe página não negativa e tamanho entre 1 e 100.");
        return records.findByAgendamento_IdAndUnidade(id,scope.unidadePainel().valor(),
            PageRequest.of(page,size,Sort.by(Sort.Order.desc("ocorridoEm"),Sort.Order.desc("versaoResultante"),Sort.Order.desc("id")))).map(AuditoriaRegularizacaoDTO::new);
    }
}
