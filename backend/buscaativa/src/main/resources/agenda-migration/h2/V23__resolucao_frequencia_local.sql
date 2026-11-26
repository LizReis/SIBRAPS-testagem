-- Estrutura aditiva: nenhuma resolução histórica criada pela migração.
CREATE TABLE tb_resolucao_frequencia_local (
    id UUID PRIMARY KEY,
    vinculo_id BIGINT NOT NULL,
    paciente_id BIGINT NOT NULL,
    unidade_chave VARCHAR(80) NOT NULL,
    fonte_valida VARCHAR(30) NOT NULL CHECK (fonte_valida IN ('ATENDIMENTO_INDIVIDUAL','SESSAO_GRUPO')),
    fato_valido BIGINT NOT NULL CHECK (fato_valido > 0),
    versao_valida INTEGER NOT NULL CHECK (versao_valida >= 0),
    frequencia_valida VARCHAR(10) NOT NULL CHECK (frequencia_valida IN ('PRESENTE','FALTOU')),
    responsavel_valido BIGINT NOT NULL REFERENCES tb_usuario(id),
    fonte_desconsiderada VARCHAR(30) NOT NULL CHECK (fonte_desconsiderada IN ('ATENDIMENTO_INDIVIDUAL','SESSAO_GRUPO')),
    fato_desconsiderado BIGINT NOT NULL CHECK (fato_desconsiderado > 0),
    versao_desconsiderada INTEGER NOT NULL CHECK (versao_desconsiderada >= 0),
    frequencia_desconsiderada VARCHAR(10) NOT NULL CHECK (frequencia_desconsiderada IN ('PRESENTE','FALTOU')),
    responsavel_desconsiderado BIGINT NOT NULL REFERENCES tb_usuario(id),
    horario TIMESTAMP NOT NULL,
    ator_id BIGINT NOT NULL REFERENCES tb_usuario(id),
    perfil VARCHAR(30) NOT NULL CHECK (perfil IN ('ADMINISTRADOR','PROFISSIONAL')),
    justificativa VARCHAR(2000) NOT NULL CHECK (LENGTH(TRIM(justificativa)) BETWEEN 10 AND 2000),
    registrado_em TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_resolucao_vinculo_local FOREIGN KEY (vinculo_id,paciente_id,unidade_chave)
        REFERENCES tb_vinculo_assistencial(id,paciente_id,unidade_chave),
    CONSTRAINT uk_resolucao_fato_desconsiderado UNIQUE (fonte_desconsiderada,fato_desconsiderado),
    CONSTRAINT ck_resolucao_fatos_distintos CHECK (fonte_valida<>fonte_desconsiderada OR fato_valido<>fato_desconsiderado),
    CONSTRAINT ck_resolucao_perfil CHECK (perfil='ADMINISTRADOR' OR
        (ator_id=responsavel_valido AND ator_id=responsavel_desconsiderado))
);
CREATE INDEX ix_resolucao_vinculo_local ON tb_resolucao_frequencia_local(vinculo_id,registrado_em);
CREATE TRIGGER tr_resolucao_frequencia_append_only
    BEFORE UPDATE, DELETE ON tb_resolucao_frequencia_local
    FOR EACH ROW CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger';
