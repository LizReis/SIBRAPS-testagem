-- Aplicação explícita de H1: não sobrescrever a origem bruta nem fazer backfill.
ALTER TABLE tb_prova_aprovacao_origem ADD CONSTRAINT uk_prova_aplicacao_origem
    UNIQUE (decisao_id,pendencia_id,vinculo_id,paciente_id,unidade_chave,historico_id);
CREATE TABLE tb_aplicacao_origem_historica (
    id UUID PRIMARY KEY,
    decisao_id UUID NOT NULL UNIQUE,
    pendencia_id UUID NOT NULL UNIQUE,
    vinculo_id BIGINT NOT NULL,
    paciente_id BIGINT NOT NULL,
    unidade_chave VARCHAR(80) NOT NULL,
    historico_id BIGINT NOT NULL UNIQUE REFERENCES tb_historico_paciente(id),
    ator_id BIGINT NOT NULL REFERENCES tb_usuario(id),
    perfil VARCHAR(20) NOT NULL CHECK (perfil='ADMINISTRADOR'),
    justificativa VARCHAR(2000) NOT NULL CHECK (LENGTH(TRIM(justificativa)) BETWEEN 10 AND 2000),
    registrado_em TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_aplicacao_prova_origem FOREIGN KEY
        (decisao_id,pendencia_id,vinculo_id,paciente_id,unidade_chave,historico_id)
        REFERENCES tb_prova_aprovacao_origem
        (decisao_id,pendencia_id,vinculo_id,paciente_id,unidade_chave,historico_id)
);
CREATE INDEX ix_aplicacao_origem_vinculo ON tb_aplicacao_origem_historica(vinculo_id,paciente_id,unidade_chave);
CREATE TRIGGER tr_aplicacao_origem_append_only BEFORE UPDATE, DELETE
    ON tb_aplicacao_origem_historica FOR EACH ROW CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger';
