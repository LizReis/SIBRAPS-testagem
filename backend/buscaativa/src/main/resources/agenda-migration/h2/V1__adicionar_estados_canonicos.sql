-- Additive only. Backfill is a separate version; existing audit revisions remain untouched.
ALTER TABLE tb_agendamento ADD COLUMN IF NOT EXISTS situacao_reserva VARCHAR(20);
ALTER TABLE tb_agendamento ADD COLUMN IF NOT EXISTS frequencia_assistencial VARCHAR(20);
ALTER TABLE tb_agendamento ADD COLUMN IF NOT EXISTS migracao_pendente BOOLEAN;
ALTER TABLE tb_agendamento ADD COLUMN IF NOT EXISTS motivo_migracao_pendente VARCHAR(200);
ALTER TABLE tb_agendamento ADD COLUMN IF NOT EXISTS ocupa_vaga BOOLEAN;
ALTER TABLE tb_agendamento_aud ADD COLUMN IF NOT EXISTS situacao_reserva VARCHAR(20);
ALTER TABLE tb_agendamento_aud ADD COLUMN IF NOT EXISTS frequencia_assistencial VARCHAR(20);
ALTER TABLE tb_agendamento_aud ADD COLUMN IF NOT EXISTS migracao_pendente BOOLEAN;
ALTER TABLE tb_agendamento_aud ADD COLUMN IF NOT EXISTS motivo_migracao_pendente VARCHAR(200);
ALTER TABLE tb_agendamento_aud ADD COLUMN IF NOT EXISTS ocupa_vaga BOOLEAN;
ALTER TABLE tb_paciente ADD COLUMN IF NOT EXISTS indicadores_pendentes BOOLEAN DEFAULT FALSE;
ALTER TABLE tb_paciente_aud ADD COLUMN IF NOT EXISTS indicadores_pendentes BOOLEAN;
