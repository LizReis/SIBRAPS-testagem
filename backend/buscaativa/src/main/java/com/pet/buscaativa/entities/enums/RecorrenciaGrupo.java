package com.pet.buscaativa.entities.enums;

public enum RecorrenciaGrupo {
    UNICA(1),
    SEMANAL(2),
    QUINZENAL(3),
    MENSAL(4);

    private int codigo;

    RecorrenciaGrupo(int codigo) {
        this.codigo = codigo;
    }

    public int getCodigo() {
        return codigo;
    }

    public static RecorrenciaGrupo valueOf(int codigo) {
        for (RecorrenciaGrupo value : RecorrenciaGrupo.values()) {
            if (value.getCodigo() == codigo) {
                return value;
            }
        }
        throw new IllegalArgumentException("Código de Recorrencia inválido!");
    }
}
