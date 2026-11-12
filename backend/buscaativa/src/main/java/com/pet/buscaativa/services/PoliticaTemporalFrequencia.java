package com.pet.buscaativa.services;

import java.time.*;
import org.springframework.stereotype.Service;
import com.pet.buscaativa.config.AgendaTempoProperties;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.services.exceptions.*;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PoliticaTemporalFrequencia {
    private final Clock clock;
    private final AgendaTempoProperties tempo;
    public void validarAgendamento(Agendamento a,boolean correcao) {
        validar(a.getDataAgendamento(),a.getHoraAtendimento(),correcao,a.getId(),null);
    }
    public void validarSessao(SessaoGrupo s,boolean correcao) {
        validar(s.getDataSessao(),s.getHorario(),correcao,null,s.getId());
    }
    public void validarRecuperacao(Agendamento a) {
        var previsto=previsto(a.getDataAgendamento(),a.getHoraAtendimento(),a.getId(),null);
        if(!previsto.isAfter(LocalDateTime.ofInstant(clock.instant(),tempo.getFuso())))
            throw new ConflictException("Recuperação exige horário estritamente futuro; nenhum fato assistencial foi presumido.");
    }
    private LocalDateTime previsto(LocalDate data,LocalTime hora,Long agendamento,Long sessao) {
        if(data==null || hora==null)throw horarioPendente(agendamento,sessao);
        var previsto=LocalDateTime.of(data,hora);
        if(tempo.getFuso().getRules().getValidOffsets(previsto).size()!=1)throw horarioPendente(agendamento,sessao);
        return previsto;
    }
    private void validar(LocalDate data,LocalTime hora,boolean correcao,Long agendamento,Long sessao) {
        var previsto=previsto(data,hora,agendamento,sessao);
        var agora=LocalDateTime.ofInstant(clock.instant(),tempo.getFuso());
        if(correcao ? !previsto.isBefore(agora) : previsto.isAfter(agora))
            throw new ValidationException(correcao
                ? "A correção de frequência só é permitida após o horário previsto do atendimento."
                : "O registro inicial de frequência só é permitido a partir do horário previsto do atendimento.");
    }
    private PendenciaFrequenciaException horarioPendente(Long a,Long s) {
        return new PendenciaFrequenciaException("HORARIO_ASSISTENCIAL_PENDENTE_RECONCILIACAO","HORARIO_ASSISTENCIAL",a,s);
    }
}
