-- V13: apenas estrutura aditiva para FUTURA decisao HUMANA sobre uma pendencia V12.
-- Migracao exclusivamente OFFLINE, nunca executada no startup da aplicacao.
-- Nenhuma decisao retroativa, nenhum backfill, nenhuma correcao clinica,
-- nenhuma atribuicao automatica de unidade/proveniencia historica.
--
-- A solicitacao V12 permanece append-only e com estado PENDENTE para sempre:
-- a decisao, quando futuramente autorizada, sera um EVENTO separado.
-- Antes de ativar a fila HTTP, ajustar a leitura para excluir pendencias
-- ja decididas; NAO atualizar o estado imutavel da solicitacao original.
ALTER TABLE tb_pendencia_revisao_proveniencia
    ADD CONSTRAINT uk_pendencia_decisao_escopo UNIQUE (id, paciente_id, unidade_chave);

CREATE TABLE tb_decisao_revisao_proveniencia (
    id UUID PRIMARY KEY,
    pendencia_id UUID NOT NULL,
    paciente_id BIGINT NOT NULL,
    unidade_chave VARCHAR(80) NOT NULL,
    decisao VARCHAR(20) NOT NULL,
    justificativa VARCHAR(2000) NOT NULL,
    revisor_id BIGINT NOT NULL,
    perfil_revisor VARCHAR(30) NOT NULL,
    decidida_em TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_decisao_revisao_ticket UNIQUE (pendencia_id),
    CONSTRAINT fk_decisao_revisao_pendencia_local
        FOREIGN KEY (pendencia_id, paciente_id, unidade_chave)
        REFERENCES tb_pendencia_revisao_proveniencia(id, paciente_id, unidade_chave),
    CONSTRAINT fk_decisao_revisao_revisor
        FOREIGN KEY (revisor_id) REFERENCES tb_usuario(id),
    CONSTRAINT ck_decisao_revisao_integridade CHECK (
        LENGTH(TRIM(unidade_chave)) > 0
        AND decisao IN ('APROVAR', 'REJEITAR')
        AND LENGTH(TRIM(justificativa)) BETWEEN 10 AND 2000
        -- Defesa em profundidade contra justificativas genericas, inclusive
        -- por SQL direto. A politica Java continua responsavel por normalizar
        -- espacos, Unicode e caracteres de controle antes da gravacao.
        AND LOWER(TRIM(justificativa)) NOT IN (
            'sem motivo', 'nao informado', 'não informado',
            'correcao', 'correção', 'alteracao', 'alteração'
        )
        AND perfil_revisor = 'ADMINISTRADOR'
    )
);

CREATE INDEX ix_decisao_revisao_unidade_data
    ON tb_decisao_revisao_proveniencia(unidade_chave, decidida_em, id);

-- Fisicamente imutavel ate mesmo por DML direto no H2.
CREATE TRIGGER tr_decisao_revisao_append_only
    BEFORE UPDATE, DELETE ON tb_decisao_revisao_proveniencia
    FOR EACH ROW CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger';

-- Estrutura para Envers futura. Ainda NAO ha entidade JPA/servico para gravar.
CREATE TABLE tb_decisao_revisao_proveniencia_aud (
    id UUID NOT NULL,
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    pendencia_id UUID,
    paciente_id BIGINT,
    unidade_chave VARCHAR(80),
    decisao VARCHAR(20),
    justificativa VARCHAR(2000),
    revisor_id BIGINT,
    perfil_revisor VARCHAR(30),
    decidida_em TIMESTAMP(6) WITH TIME ZONE,
    PRIMARY KEY (id, rev),
    CONSTRAINT fk_decisao_revisao_aud_rev
        FOREIGN KEY (rev) REFERENCES revinfo(rev)
);
