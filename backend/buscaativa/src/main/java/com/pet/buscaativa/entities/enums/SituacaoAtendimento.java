package com.pet.buscaativa.entities.enums;

public enum SituacaoAtendimento {

    PRESENTE(1),
    AGENDADO(2),
    FALTOU(3),
    REMARCADO(4),
    CANCELADO(5),
    REMARCADO_ORIGEM(6);

    private int codigo;

    SituacaoAtendimento(int codigo) {
        this.codigo = codigo;
    }

    public int getCodigo() {
        return codigo;
    }

    public static SituacaoAtendimento valueOf(int codigo) {
        for (SituacaoAtendimento value : SituacaoAtendimento.values()) {
            if (value.getCodigo() == codigo) {
                return value;
            }
        }
        throw new IllegalArgumentException("Código de Atendimento não identificado!");
    }
}
