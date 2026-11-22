-- V12: estrutura ADITIVA para futuras solicitacoes de conciliacao de origem.
-- A ferramenta AgendaMigrationTool e OFFLINE; nunca executar no startup.
-- Sem dados migrados, sem origem clinica inferida, sem pendencias artificiais.
-- O registro da solicitacao sera imutavel; a futura decisao exigira trilha
-- append-only separada, mediante validacao humana e escopo da propria unidade.
--
-- Garante que uma solicitacao nao associe paciente ou unidade diferentes
-- dos presentes em seu VINCULO assistencial. A unicidade redundante e
-- necessaria para a FK composta e nao altera dados clinicos.
ALTER TABLE tb_vinculo_assistencial
    ADD CONSTRAINT uk_vinculo_revisao_identidade UNIQUE (id, paciente_id, unidade_chave);

CREATE TABLE tb_pendencia_revisao_proveniencia (
    id UUID PRIMARY KEY,
    vinculo_id BIGINT NOT NULL,
    paciente_id BIGINT NOT NULL,
    unidade_chave VARCHAR(80) NOT NULL,
    tipo_registro VARCHAR(20) NOT NULL,
    registro_id BIGINT NOT NULL,
    situacao_evidencia VARCHAR(50) NOT NULL,
    justificativa VARCHAR(2000) NOT NULL,
    solicitante_id BIGINT NOT NULL,
    perfil_solicitante VARCHAR(30) NOT NULL,
    solicitado_em TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'PENDENTE',
    CONSTRAINT fk_pendencia_revisao_vinculo
        FOREIGN KEY (vinculo_id, paciente_id, unidade_chave)
        REFERENCES tb_vinculo_assistencial(id, paciente_id, unidade_chave),
    CONSTRAINT fk_pendencia_revisao_solicitante FOREIGN KEY (solicitante_id)
        REFERENCES tb_usuario(id),
    CONSTRAINT ck_pendencia_revisao_integridade CHECK (
        LENGTH(TRIM(unidade_chave)) > 0
        AND tipo_registro IN ('AGENDAMENTO', 'HISTORICO', 'SESSAO_GRUPO')
        AND registro_id > 0
        AND situacao_evidencia IN (
            'REVISAR_ORIGEM_DESCONHECIDA',
            'REVISAR_FONTE_SEM_ORIGEM',
            'REVISAR_ORIGENS_CONFLITANTES',
            'REVISAR_INTEGRIDADE_DA_FONTE',
            'COMPATIVEL_SOMENTE_PARA_CONFERENCIA'
        )
        AND LENGTH(TRIM(justificativa)) BETWEEN 10 AND 2000
        AND perfil_solicitante IN ('ADMINISTRADOR', 'PROFISSIONAL')
        AND estado = 'PENDENTE'
    )
);
CREATE INDEX ix_pendencia_revisao_vinculo ON tb_pendencia_revisao_proveniencia(vinculo_id, solicitado_em, id);
CREATE INDEX ix_pendencia_revisao_fato ON tb_pendencia_revisao_proveniencia(tipo_registro, registro_id);

-- Defesa em profundidade: uma solicitacao antiga nao pode ser editada
-- ou excluida silenciosamente. A futura decisao sera uma nova entidade.
CREATE TRIGGER tr_pendencia_revisao_append_only
    BEFORE UPDATE, DELETE ON tb_pendencia_revisao_proveniencia
    FOR EACH ROW CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger';

-- Envers audita INSERT do objeto sem simular decisoes historicas.
CREATE TABLE tb_pendencia_revisao_proveniencia_aud (
    id UUID NOT NULL,
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    vinculo_id BIGINT,
    paciente_id BIGINT,
    unidade_chave VARCHAR(80),
    tipo_registro VARCHAR(20),
    registro_id BIGINT,
    situacao_evidencia VARCHAR(50),
    justificativa VARCHAR(2000),
    solicitante_id BIGINT,
    perfil_solicitante VARCHAR(30),
    solicitado_em TIMESTAMP(6) WITH TIME ZONE,
    estado VARCHAR(20),
    PRIMARY KEY (id, rev),
    CONSTRAINT fk_pendencia_revisao_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);
