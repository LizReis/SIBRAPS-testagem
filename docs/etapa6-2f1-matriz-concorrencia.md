# Etapa 6.2F1 — matriz original de concorrência assistencial

**Commit-base:** e2c3e131cbb40abb193f6f222583d1f61638d9ed. Etapa 6.2E aprovada no escopo direcionado (Actions 44425631305): 13/13 remarcações e 148/148 regressões, sem erros. A cadeia RF-07 já persistida permanece pendente até 6.5/6.6.

## Escopo estrito F1

Reexecutar integralmente a `ReconciliationConcurrency61Characterization`, sem modificar suas expectativas: CO-01 (consulta×inscrição), CO-02 (consulta×coordenador), CO-03 (remarcação×inscrição), CO-04 (recuperação×inscrição), CO-05 (recuperação×criação de grupo), CO-06 (inscrições em grupos distintos), CO-07 (duas sessões do coordenador), CO-08 (transferência×consulta), CO-09 (correção×confirmação), CO-10 (duas correções factuais), CO-11 (regularização×retroativa), CO-13 (operações independentes). São 12 cenários de corrida e 24 operações (um método parametrizado para CO-04/05).

Os testes usam H2 de teste, transações e conexões independentes, barreira de sincronização controlada, verificação após commit e timeouts definidos no suporte. O helper `race` não aceita erro técnico de lock convertido em resultado funcional e testa a contagem de confirmações e estado final. A falha deverá ser investigada pelo ID e pelo motivo, não escondida por retry ou mudança da expectativa.

Regressões inicialmente selecionadas: `AgendaConcurrencyTests`, `AgendaCombinedTests`, `AgendaRescheduleTests`, `GroupHistoricalResponsibilityTests`, `FrequencyCorrectionConcurrencyTests`, `ClinicalAuditIntegrationTests`, `GroupFrequencyTemporalTests`, `CancellationConcurrencyTests`. Sem executar `mvn verify` integral, Angular ou `clean` nesta entrega.

## Pendências para F2 e F3, condicionadas ao resultado da F1

- Novas disputas: regeneração×consulta, transferência×inscrição, criação de próxima sessão×criação, edição de perfil×reserva, diferentes ordens de execução, operações independentes e lote com alteração de participantes;
- Injeção de falhas após escritas reais de histórico/auditoria e comparação de snapshots completos, sem exigir recuperação de sequências técnicas de IDs;
- Matriz HTTP e serviços para 401/403/404/409/422, USFs distintas e responsável histórico transferido; sigilo de nomes, IDs e fatos de terceiros.
- Não alterar regras clínicas ou estados canônicos por conveniência técnica; não alterar migrações, frontend, `main` ou proteção histórica RF-07 nesta subetapa.

**Situação:** F1 apenas configurada para nova execução. Não afirmar aprovação antes de verificar os logs completos; F2/F3 não iniciadas.
