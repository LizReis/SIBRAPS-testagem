package com.pet.buscaativa.entities.enums;

public enum MotivoEncerramento {
    ALTA_TERAPEUTICA(1),
    TRANSFERENCIA_OUTRA_UNIDADE(2),
    ABANDONO_TRATAMENTO(3),
    OBITO(4),
    OUTRO(5);

    private int codigo;

    MotivoEncerramento(int codigo) {
        this.codigo = codigo;
    }

    public int getCodigo() {
        return codigo;
    }

    public static MotivoEncerramento valueOf(int codigo) {
        for (MotivoEncerramento value : MotivoEncerramento.values()) {
            if (value.getCodigo() == codigo) {
                return value;
            }
        }
        throw new IllegalArgumentException("Código de Motivo inválido!");
    }
}