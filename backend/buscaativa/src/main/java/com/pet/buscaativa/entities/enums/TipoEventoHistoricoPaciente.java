package com.pet.buscaativa.entities.enums;

public enum TipoEventoHistoricoPaciente {
    CONSULTA_AGENDADA(1),
    PRESENCA(2),
    FALTA(3),
    REMARCACAO(4),
    PARTICIPACAO_GRUPO_TERAPEUTICO(5),
    SITUACAO_ATUALIZADA(6),
    BUSCA_ATIVA(7);

    private int codigo;

    TipoEventoHistoricoPaciente(int codigo) {
        this.codigo = codigo;
    }

    public int getCodigo() {
        return codigo;
    }

    public static TipoEventoHistoricoPaciente valueOf(int codigo) {
        for (TipoEventoHistoricoPaciente value : TipoEventoHistoricoPaciente.values()) {
            if (value.getCodigo() == codigo) {
                return value;
            }
        }
        throw new IllegalArgumentException("Código de Usuário Inválido!");
    }
}