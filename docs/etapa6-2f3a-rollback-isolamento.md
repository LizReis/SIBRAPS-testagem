# Etapa 6.2F3A — rollback real, autorização e isolamento

**Base:** 7770daaae341688de09ceb06257c62ef07f0d88a. Etapa F2 aprovada no GitHub Actions 44431066715: 9/9 novas disputas, 131/131 regressões e 0 falhas; F1: 12/12 disputas originais e 139/139 regressões. Esta entrega não altera código de produção, regras assistenciais, indicadores, políticas de risco, frontend, migrações, POM nem testes preexistentes.

## Novos testes
Classe `AtomicityIsolation62Tests`, oito invocações:
- Falha injetada após **duas escritas reais** no histórico de uma inscrição retroativa em duas sessões realizadas. Exigir duas chamadas ao escritor, lançar exceção controlada após a segunda e comparar snapshot completo de todas as tabelas (e não só contagens), incluindo histórico, vínculos, auditorias, versões e indicadores. O teste não exige rollback de sequências técnicas de IDs.
- Correção de frequência coletiva sem autenticação: HTTP 401 e snapshot imutável.
- Correção de grupo por colega, recepção e administrador de outra unidade: HTTP 403/403/404 e nenhuma alteração; justificativa não exposta.
- Chamada direta ao serviço por colega ou recepção: acesso negado e snapshot imutável.
- Mensagem de conflito de horário não revela nome ou UUID de paciente terceiro.

## Reuso de provas existentes
Regressões selecionadas: `ClinicalAuditIntegrationTests` (falha após inserção em auditoria e histórico, rollback de lotes), `SpecificFrequencyCorrectionTests` (falha após flush, histórico e auditoria), `CancellationSecurityAuditTests` (falhas depois de gravações reais e permissões), `GroupHistoricalResponsibilityTests` (responsável histórico, transferência, USFs diferentes, rollback em confirmação), `IsolamentoUnidadeTests`, `ClinicalAuthorizationTests`, `RecordedFrequencyCancellationTests`, `GroupFactual62Tests`, `CancellationConcurrencyTests` e `ReconciliationConcurrency61Characterization` (matriz original). Essas classes já existiam e não foram editadas.

## Limites e pendências
Esta validação verifica rollback e autorização no escopo selecionado, mas não a suíte integral. As simulações adicionais de inversão controlada de interleavings, alteração de perfil × reserva, preparação de participantes com alteração concorrente e preservação dos snapshots para falhas muito tardias em série/regeneração permanecem para avaliação após os resultados de F3A. Não tratar a cadeia histórica RF-07 nem os empates de indicadores, reservados às etapas posteriores. Não converter deadlock/timeout em sucesso de negócio e não alterar expectativas para acomodar defeitos.

**Status:** resultados GitHub Actions pendentes.
