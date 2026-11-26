-- Evidência específica da aprovação H1. Não aplicar nem preencher origem.
-- A decisão e o ticket devem pertencer ao mesmo paciente e unidade, e
-- a categoria do ticket não pode ser usada para autorizar uma duplicidade.
ALTER TABLE tb_pendencia_revisao_proveniencia ADD CONSTRAINT uk_pendencia_prova_origem
    UNIQUE (id,vinculo_id,paciente_id,unidade_chave,tipo_registro,registro_id,situacao_evidencia);
CREATE TABLE tb_prova_aprovacao_origem (
    decisao_id UUID PRIMARY KEY,
    pendencia_id UUID NOT NULL UNIQUE,
    vinculo_id BIGINT NOT NULL,
    paciente_id BIGINT NOT NULL,
    unidade_chave VARCHAR(80) NOT NULL,
    tipo_registro VARCHAR(20) NOT NULL CHECK (tipo_registro='HISTORICO'),
    historico_id BIGINT NOT NULL CHECK (historico_id>0),
    situacao_evidencia VARCHAR(50) NOT NULL CHECK (situacao_evidencia='REVISAR_ORIGEM_DESCONHECIDA'),
    decisao VARCHAR(20) NOT NULL CHECK (decisao='APROVAR'),
    fonte VARCHAR(20) NOT NULL CHECK (fonte IN ('AGENDAMENTO','SESSAO_GRUPO')),
    fonte_id BIGINT NOT NULL CHECK (fonte_id>0),
    versao_fonte INTEGER NOT NULL CHECK (versao_fonte>=0),
    participante_id BIGINT,
    responsavel_id BIGINT NOT NULL REFERENCES tb_usuario(id),
    ocorrido_em TIMESTAMP NOT NULL,
    frequencia VARCHAR(20) NOT NULL CHECK (frequencia IN ('PRESENTE','FALTOU')),
    tipo_historico VARCHAR(50) NOT NULL CHECK (tipo_historico IN ('FALTA','PRESENCA','PARTICIPACAO_GRUPO_TERAPEUTICO')),
    CONSTRAINT ck_prova_fonte_participacao CHECK (
       (fonte='AGENDAMENTO' AND participante_id IS NULL) OR
       (fonte='SESSAO_GRUPO' AND participante_id IS NOT NULL AND participante_id>0)),
    CONSTRAINT fk_prova_ticket FOREIGN KEY (pendencia_id,vinculo_id,paciente_id,unidade_chave,tipo_registro,historico_id,situacao_evidencia)
       REFERENCES tb_pendencia_revisao_proveniencia(id,vinculo_id,paciente_id,unidade_chave,tipo_registro,registro_id,situacao_evidencia),
    CONSTRAINT fk_prova_decisao FOREIGN KEY (decisao_id,pendencia_id,paciente_id,unidade_chave,decisao)
       REFERENCES tb_decisao_revisao_proveniencia(id,pendencia_id,paciente_id,unidade_chave,decisao)
);
CREATE TRIGGER tr_prova_origem_append_only BEFORE UPDATE, DELETE
    ON tb_prova_aprovacao_origem FOR EACH ROW CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger';
