# Etapa 2A — consistência do gatilho de visita

Base: `aef07bc58aee401e6b95e3426650b5d35baa94cc`, branch `correcao-fluxos-assistenciais-sibraps`, checkout inicial limpo. Validação em 05/11/2026, horário de Brasília (America/Sao_Paulo). Sem merge, PR, dados reais ou alteração da main.

## Diagnóstico e regra aplicada

O recálculo de assiduidade limpava o gatilho ao encontrar qualquer presença, mas não o reconstruía ao encontrar faltas. O serviço de agenda depois sobrescrevia o resultado conforme apenas a transição corrente e `remarcacaoAposFalta`. Assim, corrigir uma falta anterior podia apagar um alerta sustentado por um par posterior de faltas; uma mudança antiga também podia reativar o alerta apesar de uma presença posterior.

Critério preservado: falta em atendimento remarcado após uma falta do mesmo paciente. Não foi criado limite numérico de visita nem confundido o alerta com risco vermelho ou visita efetivamente realizada. A regra agora exige original e sucessor cronologicamente coerentes e posteriores à última presença relevante; uma presença intermediária interrompe esse fundamento. Outro par válido posterior mantém o alerta. Presença em sessão de grupo REALIZADA participa da cronologia compartilhada; falta de grupo não cria, sozinha, o critério de remarcação individual.

`PoliticaVisitaDomiciliar` concentra a decisão, chamada pelo recálculo existente. O serviço de agenda não escreve mais o gatilho após o recálculo. Contagem, última presença, risco e gatilho são persistidos na transação existente. Locks, versão, permissões atuais e limites de risco foram preservados.

No modelo ainda legado, o original pode estar REMARCADO_ORIGEM. A política prefere seu evento clínico corrigido à flag do sucessor. Sem evento clínico sobrevivente, mantém a evidência operacional do vínculo após falta somente quando paciente e cronologia são coerentes. Isso não grava uma frequência reconstruída nem fabrica evento clínico. A classificação formal das ambiguidades e a representação da frequência original são responsabilidade da Frente B: uma flag isolada/incoerente não pode preencher automaticamente dados canônicos. Este commit não altera schema nem disponibiliza correção de cancelados/substituídos.

## Testes e evidências

| Execução | Executados | Aprovados | Falhas | Erros | Ignorados |
|---|---:|---:|---:|---:|---:|
| Baseline `verify` da Etapa 1 | 334 | 334 | 0 | 0 | 0 |
| Reprodução antes do patch, primeiros testes de visita | 11 | 4 | 7 | 0 | 0 |
| Focado após patch: visita + frequência + integridade | 89 | 89 | 0 | 0 | 0 |
| `verify` completo, incluindo três casos adicionais de grupo/unidade | 348 | 348 | 0 | 0 | 0 |

O verify terminou às **17:55:24**, em 42,329 s, com BUILD SUCCESS e empacotamento concluído. Java 21, Maven Wrapper, settings/cache da nuvem, sem skipTests. Ambiente herdado, isolamento de testes existente preservado. Logs/XMLs temporários em `/tmp/sibraps-etapa2-fluxos/`, não versionados.

Os 14 casos de `HomeVisitRecalculationTests` incluem correção anterior e mais recente, duas remarcações, par restante válido, presença posterior, evidência corrigida que contradiz flag antiga, várias faltas sem criar limite de visita, grupo presente/faltoso, unidade externa, idempotência, rollback e duas repetições concorrentes. As duas disputas novas tiveram duas operações cada, duas confirmações por disputa e nenhuma rejeição ou gravação adicional, com conexões/transações distintas e alerta final preservado pelo par ainda válido. Os testes de colisão P04 e da Etapa 1 também continuaram aprovados. Nenhuma expectativa anterior precisou ser alterada nesta frente.

Arquivos desta frente: `PoliticaVisitaDomiciliar.java` (novo), `PacienteServiceImpl.java`, `AgendamentoServiceImpl.java`, `HomeVisitRecalculationTests.java` (novo) e este documento. Os testes anteriores, segurança, entidades, enums, DTOs, frontend e handler global não foram modificados.

## Limites e continuidade

Frente A validada localmente em H2. Não há certificação de SGBD de implantação ou execução de CI remota. A próxima subetapa deve primeiro registrar consumidores, compatibilidade, ocupação, evidências e ambiguidades; só depois introduzir modelo e migração aditiva. Permissões por responsável, justificativa, auditoria detalhada, cancelamento incorreto e reconciliação administrativa continuam nas etapas futuras.
