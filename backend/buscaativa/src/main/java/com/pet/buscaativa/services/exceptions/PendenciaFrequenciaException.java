package com.pet.buscaativa.services.exceptions;

/** Refused operation, not a completed clinical correction or a durable reconciliation queue. */
public class PendenciaFrequenciaException extends ConflictException {
    private final String codigo;
    private final String tipoConflito;
    private final Long agendamentoId;
    private final Long sessaoId;
    public PendenciaFrequenciaException(String codigo,String tipo,Long agendamento,Long sessao) {
        super(codigo.startsWith("CONFLITO_")
            ? "Regularize o conflito por revisão autorizada na reconciliação; nenhuma frequência foi alterada."
            : "Horário assistencial pendente de revisão autorizada; nenhuma frequência foi alterada.");
        this.codigo=codigo;this.tipoConflito=tipo;this.agendamentoId=agendamento;this.sessaoId=sessao;
    }
    public String getCodigo(){return codigo;}
    public String getTipoConflito(){return tipoConflito;}
    public Long getAgendamentoId(){return agendamentoId;}
    public Long getSessaoId(){return sessaoId;}
}
