-- Evidência separada do escritor autorizado, sem backfill de eventos antigos.
CREATE TABLE tb_evidencia_origem_historica (
    tipo VARCHAR(12) NOT NULL CHECK (tipo IN ('APLICACAO','REVERSAO','REABERTURA')),
    evento_id UUID NOT NULL,
    vinculo_id BIGINT NOT NULL,
    paciente_id BIGINT NOT NULL,
    unidade_chave VARCHAR(80) NOT NULL,
    historico_id BIGINT NOT NULL CHECK (historico_id>0),
    assinatura VARCHAR(64) NOT NULL CHECK (REGEXP_LIKE(assinatura,'^[0-9a-f]{64}$')),
    PRIMARY KEY (tipo,evento_id),
    FOREIGN KEY (vinculo_id,paciente_id,unidade_chave)
        REFERENCES tb_vinculo_assistencial(id,paciente_id,unidade_chave)
);
CREATE INDEX ix_evidencia_origem_vinculo ON tb_evidencia_origem_historica(vinculo_id,paciente_id,unidade_chave,historico_id);
CREATE TRIGGER tr_evidencia_origem_append_only BEFORE UPDATE, DELETE
    ON tb_evidencia_origem_historica FOR EACH ROW CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger';
