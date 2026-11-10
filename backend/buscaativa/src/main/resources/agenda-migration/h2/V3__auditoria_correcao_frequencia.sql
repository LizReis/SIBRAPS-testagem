-- Additive correction trail. No reconstruction of reasons/authors for historical corrections.
CREATE TABLE tb_auditoria_correcao_frequencia (
    id UUID PRIMARY KEY,
    agendamento_id BIGINT REFERENCES tb_agendamento(id),
    sessao_id BIGINT REFERENCES tb_sessao_grupo(id),
    participante_id BIGINT REFERENCES tb_sessao_grupo_participante(id),
    paciente_id BIGINT NOT NULL REFERENCES tb_paciente(id),
    ator_id BIGINT NOT NULL REFERENCES tb_usuario(id),
    responsavel_id BIGINT NOT NULL REFERENCES tb_usuario(id),
    perfil VARCHAR(30) NOT NULL,
    unidade VARCHAR(80) NOT NULL,
    tipo_correcao VARCHAR(20) NOT NULL,
    situacao_reserva VARCHAR(20),
    situacao_sessao VARCHAR(20),
    frequencia_anterior VARCHAR(20) NOT NULL,
    frequencia_nova VARCHAR(20) NOT NULL,
    justificativa VARCHAR(2000) NOT NULL,
    ocorrido_em TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    versao_anterior INTEGER NOT NULL,
    versao_resultante INTEGER NOT NULL,
    CONSTRAINT ck_correcao_integridade CHECK (
        perfil IN ('ADMINISTRADOR','PROFISSIONAL') AND CHAR_LENGTH(TRIM(unidade)) > 0
        AND frequencia_anterior IN ('PRESENTE','FALTOU') AND frequencia_nova IN ('PRESENTE','FALTOU')
        AND frequencia_anterior <> frequencia_nova AND CHAR_LENGTH(TRIM(justificativa)) BETWEEN 10 AND 2000
        AND versao_anterior >= 0 AND versao_resultante > versao_anterior
        AND ((tipo_correcao='INDIVIDUAL' AND agendamento_id IS NOT NULL AND sessao_id IS NULL AND participante_id IS NULL
              AND situacao_reserva IS NOT NULL AND situacao_sessao IS NULL)
          OR (tipo_correcao='GRUPO' AND agendamento_id IS NULL AND sessao_id IS NOT NULL AND participante_id IS NOT NULL
              AND situacao_reserva IS NULL AND situacao_sessao='REALIZADA')))
);
CREATE INDEX ix_correcao_agendamento ON tb_auditoria_correcao_frequencia(agendamento_id,ocorrido_em,id);
CREATE INDEX ix_correcao_sessao ON tb_auditoria_correcao_frequencia(sessao_id,ocorrido_em,id);
CREATE TRIGGER tr_correcao_append_only BEFORE UPDATE, DELETE ON tb_auditoria_correcao_frequencia
    FOR EACH ROW CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger';
