-- Evidência de aplicação, gravada pelo escritor autorizado na mesma transação.
-- Não gerar evidência retroativa para registros V23/V24 existentes.
CREATE TABLE tb_evidencia_resolucao_historica (
    tipo VARCHAR(12) NOT NULL CHECK (tipo IN ('RESOLUCAO','REVERSAO')),
    evento_id UUID NOT NULL,
    unidade_chave VARCHAR(80) NOT NULL,
    assinatura VARCHAR(64) NOT NULL CHECK (REGEXP_LIKE(assinatura,'^[0-9a-f]{64}$')),
    PRIMARY KEY (tipo,evento_id)
);
CREATE INDEX ix_evidencia_resolucao_unidade ON tb_evidencia_resolucao_historica(unidade_chave);
CREATE TRIGGER tr_evidencia_resolucao_append_only BEFORE UPDATE, DELETE
    ON tb_evidencia_resolucao_historica FOR EACH ROW CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger';
