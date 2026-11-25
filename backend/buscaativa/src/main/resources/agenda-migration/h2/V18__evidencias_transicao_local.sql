-- Evidências prospectivas e revisão RF26. Sem backfill, data padrão ou conteúdo de mensagem.
CREATE TABLE tb_marco_historico_vinculo (
    unidade VARCHAR(80) PRIMARY KEY,
    iniciado_em TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    zona VARCHAR(80) NOT NULL,
    administrador UUID NOT NULL,
    inventario CLOB NOT NULL
);

CREATE TABLE tb_fechamento_historico_vinculo (
    unidade VARCHAR(80) NOT NULL REFERENCES tb_marco_historico_vinculo(unidade),
    mes DATE NOT NULL,
    corte TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    certificado_em TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    administrador UUID NOT NULL,
    evidencia_sha256 VARCHAR(64) NOT NULL,
    inventario CLOB NOT NULL,
    indicadores CLOB NOT NULL,
    PRIMARY KEY (unidade, mes),
    CHECK (EXTRACT(DAY FROM mes) = 1),
    CHECK (certificado_em > corte)
);

CREATE TABLE tb_revisao_entrega_rf26 (
    dia DATE NOT NULL,
    destinatario UUID NOT NULL,
    unidade VARCHAR(80) NOT NULL,
    administrador UUID NOT NULL,
    revisada_em TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    estado_anterior VARCHAR(20) NOT NULL CHECK (estado_anterior IN ('RESERVADA', 'INCERTA')),
    justificativa VARCHAR(2000) NOT NULL,
    PRIMARY KEY (dia, destinatario, unidade),
    FOREIGN KEY (dia, destinatario, unidade) REFERENCES tb_entrega_rf26(dia, destinatario, unidade)
);
