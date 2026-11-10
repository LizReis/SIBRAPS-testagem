package com.pet.buscaativa.services;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.data.domain.*;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.AuditoriaCorrecaoDTO;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;
import com.pet.buscaativa.services.exceptions.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Service @RequiredArgsConstructor
public class AuditoriaCorrecaoService {
    private final EntityManager entityManager;
    private final Clock clock;
    private final EscopoUnidadeService scope;
    private final AutorizacaoClinicaService authorization;
    private final AuditoriaCorrecaoRepository records;
    private final AgendamentoRepository appointments;
    private final SessaoGrupoRepository sessions;

    @Transactional(propagation=Propagation.MANDATORY)
    public void registrarIndividual(Agendamento a,FrequenciaAssistencial before,String reason,int previousVersion) {
        authorization.validarAgendamento(a,true);
        var actor=scope.usuarioLogado();
        entityManager.persist(new AuditoriaCorrecaoFrequencia(a,null,null,a.getPaciente(),actor,a.getUsuario(),scope.unidadePainel().valor(),
                before,a.getFrequenciaAssistencial(),reason,clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),previousVersion,a.getVersion()));
        entityManager.flush();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void registrarGrupo(SessaoGrupo s,SessaoGrupoParticipante p,StatusPresencaGrupo before,String reason,int previousVersion) {
        authorization.validarSessao(s);
        var actor=scope.usuarioLogado();
        entityManager.persist(new AuditoriaCorrecaoFrequencia(null,s,p,p.getPaciente(),actor,s.getGrupo().getCoordenador(),scope.unidadePainel().valor(),
                FrequenciaAssistencial.valueOf(before.name()),FrequenciaAssistencial.valueOf(p.getStatusPresenca().name()),reason,clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),previousVersion,s.getVersion()));
        entityManager.flush();
    }
    @Transactional(readOnly=true)
    public Page<AuditoriaCorrecaoDTO> listarAgendamento(Long id,int page,int size) {
        var a=appointments.findById(id).orElseThrow(()->new ResourceNotFoundException("Atendimento não encontrado."));
        scope.validarAgendamento(a);authorization.validarResponsavel(a.getUsuario());
        return records.findByAgendamento_IdAndUnidade(id,scope.unidadePainel().valor(),pagina(page,size)).map(AuditoriaCorrecaoDTO::new);
    }
    @Transactional(readOnly=true)
    public Page<AuditoriaCorrecaoDTO> listarSessao(Long id,int page,int size) {
        var s=sessions.findById(id).orElseThrow(()->new ResourceNotFoundException("Sessão não encontrada."));
        authorization.validarSessao(s);
        return records.findBySessao_IdAndUnidade(id,scope.unidadePainel().valor(),pagina(page,size)).map(AuditoriaCorrecaoDTO::new);
    }
    private Pageable pagina(int page,int size) {
        if(page<0 || size<1 || size>100)throw new ValidationException("Informe página não negativa e tamanho entre 1 e 100.");
        return PageRequest.of(page,size,Sort.by(Sort.Order.desc("ocorridoEm"),Sort.Order.desc("versaoResultante"),Sort.Order.desc("id")));
    }
}
