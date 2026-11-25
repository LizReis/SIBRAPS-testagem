-- Fechamentos técnicos sem ator humano fictício, sem backfill e sem alterar V18.
CREATE TABLE tb_observacao_historico_mensal (
    unidade VARCHAR(80) NOT NULL,
    mes DATE NOT NULL CHECK (EXTRACT(DAY FROM mes)=1),
    fase VARCHAR(10) NOT NULL CHECK (fase IN ('PRE','FECHADO')),
    observado_em TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    assinatura VARCHAR(64) NOT NULL,
    inventario CLOB NOT NULL,
    indicadores CLOB,
    PRIMARY KEY (unidade,mes,fase),
    CHECK ((fase='PRE' AND indicadores IS NULL) OR (fase='FECHADO' AND indicadores IS NOT NULL))
);
CREATE TRIGGER tr_observacao_historico_append_only
    BEFORE UPDATE, DELETE ON tb_observacao_historico_mensal
    FOR EACH ROW CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger';
