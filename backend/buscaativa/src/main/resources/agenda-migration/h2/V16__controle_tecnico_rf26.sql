-- Controle técnico do ensaio RF26. Sem conteúdo clínico e sem backfill.
CREATE TABLE tb_entrega_rf26 (
    dia DATE NOT NULL,
    destinatario UUID NOT NULL,
    unidade VARCHAR(80) NOT NULL,
    estado VARCHAR(20) NOT NULL,
    reservada_em TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    finalizada_em TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_entrega_rf26 PRIMARY KEY (dia, destinatario, unidade),
    CONSTRAINT ck_entrega_rf26_unidade CHECK (
        unidade IN ('CAPS_I', 'CAPS_II', 'CAPS_AD') OR REGEXP_LIKE(unidade, '^USF:[1-9][0-9]*$')
    ),
    CONSTRAINT ck_entrega_rf26_estado CHECK (
        (estado = 'RESERVADA' AND finalizada_em IS NULL)
        OR (estado IN ('SIMULADA', 'INVALIDADA', 'INCERTA')
            AND finalizada_em IS NOT NULL AND finalizada_em >= reservada_em)
    )
);
