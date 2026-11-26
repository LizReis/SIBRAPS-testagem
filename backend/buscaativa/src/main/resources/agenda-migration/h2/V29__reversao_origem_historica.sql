-- Reversão administrativa explícita, sem alteração da aprovação ou origem bruta.
ALTER TABLE tb_aplicacao_origem_historica ADD CONSTRAINT uk_aplicacao_origem_reversao
    UNIQUE (id,vinculo_id,paciente_id,unidade_chave,historico_id);
CREATE TABLE tb_reversao_origem_historica (
    id UUID PRIMARY KEY,
    aplicacao_id UUID NOT NULL UNIQUE,
    vinculo_id BIGINT NOT NULL,
    paciente_id BIGINT NOT NULL,
    unidade_chave VARCHAR(80) NOT NULL,
    historico_id BIGINT NOT NULL,
    ator_id BIGINT NOT NULL REFERENCES tb_usuario(id),
    perfil VARCHAR(20) NOT NULL CHECK (perfil='ADMINISTRADOR'),
    justificativa VARCHAR(2000) NOT NULL CHECK (LENGTH(TRIM(justificativa)) BETWEEN 10 AND 2000),
    registrado_em TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_reversao_aplicacao_origem FOREIGN KEY
        (aplicacao_id,vinculo_id,paciente_id,unidade_chave,historico_id)
        REFERENCES tb_aplicacao_origem_historica(id,vinculo_id,paciente_id,unidade_chave,historico_id)
);
CREATE TRIGGER tr_reversao_origem_append_only BEFORE UPDATE, DELETE
    ON tb_reversao_origem_historica FOR EACH ROW CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger';
