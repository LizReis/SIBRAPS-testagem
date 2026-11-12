-- Additive only. No backfill, presumed clinical facts, reasons or historical regularizations.
ALTER TABLE tb_agendamento ADD CONSTRAINT uk_agendamento_identidade UNIQUE(id,paciente_id,usuario_id);
CREATE TABLE tb_auditoria_regularizacao_cancelamento (
 id UUID PRIMARY KEY,
 agendamento_id BIGINT NOT NULL REFERENCES tb_agendamento(id),
 paciente_id BIGINT NOT NULL REFERENCES tb_paciente(id),
 ator_id BIGINT NOT NULL REFERENCES tb_usuario(id),
 responsavel_id BIGINT NOT NULL REFERENCES tb_usuario(id),
 perfil VARCHAR(30) NOT NULL,
 unidade VARCHAR(80) NOT NULL,
 tipo_regularizacao VARCHAR(40) NOT NULL,
 reserva_anterior VARCHAR(20) NOT NULL,
 reserva_resultante VARCHAR(20) NOT NULL,
 frequencia_anterior VARCHAR(20) NOT NULL,
 frequencia_resultante VARCHAR(20) NOT NULL,
 justificativa VARCHAR(2000) NOT NULL,
 ocorrido_em TIMESTAMP(6) WITH TIME ZONE NOT NULL,
 versao_anterior INTEGER NOT NULL,
 versao_resultante INTEGER NOT NULL,
 CONSTRAINT uk_regularizacao_versao UNIQUE(agendamento_id,versao_anterior),
 CONSTRAINT fk_regularizacao_identidade FOREIGN KEY(agendamento_id,paciente_id,responsavel_id) REFERENCES tb_agendamento(id,paciente_id,usuario_id),
 CONSTRAINT ck_regularizacao_integridade CHECK (
 perfil IN ('ADMINISTRADOR','PROFISSIONAL') AND CHAR_LENGTH(TRIM(unidade))>0
 AND reserva_anterior='CANCELADA' AND frequencia_anterior='NAO_REGISTRADA'
 AND CHAR_LENGTH(TRIM(justificativa)) BETWEEN 10 AND 2000
 AND versao_anterior>=0 AND versao_resultante=versao_anterior+1
 AND ((tipo_regularizacao='RECUPERACAO_RESERVA' AND reserva_resultante IN ('AGENDADA','REMARCADA') AND frequencia_resultante='NAO_REGISTRADA')
 OR (tipo_regularizacao='REGISTRO_FATO_ASSISTENCIAL' AND reserva_resultante='CANCELADA' AND frequencia_resultante IN ('PRESENTE','FALTOU'))))
);
CREATE INDEX ix_regularizacao_agendamento ON tb_auditoria_regularizacao_cancelamento(agendamento_id,ocorrido_em,id);
CREATE TRIGGER tr_regularizacao_append_only BEFORE UPDATE, DELETE ON tb_auditoria_regularizacao_cancelamento
 FOR EACH ROW CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger';
