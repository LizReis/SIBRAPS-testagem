-- Strengthen the existing group audit check additively; V3 remains immutable.
ALTER TABLE tb_auditoria_correcao_frequencia ADD CONSTRAINT ck_correcao_grupo_confirmado
    CHECK (tipo_correcao <> 'GRUPO' OR situacao_sessao IS NOT NULL);

-- Operational alert trail. No backfill: list consultation is not individual viewing or forwarding.
CREATE TABLE tb_auditoria_busca_ativa (
    id UUID PRIMARY KEY,
    paciente_id BIGINT REFERENCES tb_paciente(id),
    ator_id BIGINT NOT NULL REFERENCES tb_usuario(id),
    perfil VARCHAR(30) NOT NULL,
    unidade VARCHAR(80) NOT NULL,
    tipo_evento VARCHAR(40) NOT NULL,
    chave_requisicao UUID NOT NULL,
    ocorrido_em TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    situacao VARCHAR(80) NOT NULL,
    risco VARCHAR(20),
    gatilho_confirmado BOOLEAN NOT NULL,
    indicadores_pendentes BOOLEAN NOT NULL,
    situacao_rua BOOLEAN NOT NULL,
    quantidade INTEGER NOT NULL,
    destino VARCHAR(200),
    descricao VARCHAR(1000),
    CONSTRAINT uk_evento_ator_chave UNIQUE(ator_id,chave_requisicao),
    CONSTRAINT ck_evento_busca_integridade CHECK (
        quantidade >= 0 AND CHAR_LENGTH(TRIM(unidade)) > 0 AND perfil IN ('ADMINISTRADOR','PROFISSIONAL','RECEPCAO')
        AND (NOT gatilho_confirmado OR NOT indicadores_pendentes)
        AND ((tipo_evento='CONSULTA_LISTA_BUSCA_ATIVA' AND paciente_id IS NULL AND destino IS NULL AND descricao IS NULL)
          OR (tipo_evento='VISUALIZACAO_ALERTA' AND paciente_id IS NOT NULL AND destino IS NULL AND descricao IS NULL AND quantidade=1)
          OR (tipo_evento='ENCAMINHAMENTO_ALERTA' AND paciente_id IS NOT NULL AND destino IS NOT NULL AND descricao IS NOT NULL AND CHAR_LENGTH(TRIM(destino)) BETWEEN 1 AND 200
              AND CHAR_LENGTH(TRIM(descricao)) BETWEEN 10 AND 1000 AND quantidade=1 AND perfil IN ('ADMINISTRADOR','PROFISSIONAL') AND NOT indicadores_pendentes)))
);
CREATE INDEX ix_evento_paciente ON tb_auditoria_busca_ativa(paciente_id,unidade,ocorrido_em,id);
CREATE INDEX ix_evento_unidade ON tb_auditoria_busca_ativa(unidade,ocorrido_em,id);
CREATE TRIGGER tr_busca_ativa_append_only BEFORE UPDATE, DELETE ON tb_auditoria_busca_ativa
    FOR EACH ROW CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger';
