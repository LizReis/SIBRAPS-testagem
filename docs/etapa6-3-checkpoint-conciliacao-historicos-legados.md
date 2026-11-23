# Etapa 6.3 — checkpoint para conciliação humana dos históricos antigos

**Data:** 23/11/2026. **Branch:** `correcao-cadastro-unico-isolamento`.
**Base aprovada:** commit `1277c5483f0b358b74659fcd3e7c4f0f58ea89dd`, Actions #45264281787 (1.253 testes Maven aprovados, concorrência, build Angular e testes direcionados).
**Status:** análise de requisitos e dependências; NÃO autoriza backfill, alteração de fatos, ativação de tela ou migração de banco real.

## Regras funcionais já acordadas

1. Uma identidade cadastral global única por CPF/CNS; vínculo assistencial explícito e independente em cada USF e CAPS I/II/AD. Dados cadastrais podem ser compartilhados dentro da autorização existente; fatos, faltas, frequência, grupos, risco, alertas e histórico clínico ficam isolados por unidade.
2. `capsReferencia`, `usfReferencia`, lotação atual, profissional de referência e coordenação atual NÃO comprovam unidade histórica. Nunca usá-los para backfill ou liberação automática.
3. Fatos antigos com origem ausente ou conflitante permanecem preservados e pendentes de revisão com evidências; sem exclusão e sem fusão automática. Histórico manual legítimo não é necessariamente erro.
4. Regra escolhida: opção B — justificativa escrita, revisão humana posterior e registro auditável; uma decisão administrativa isolada não corrige automaticamente um atendimento.
5. Preservar RF22, RNF17, sequência de frequências, remarcações, cancelamentos, visita domiciliar, risco e busca ativa conforme regras já aprovadas.

## Inventário de implementação já validada

- Migrações V8–V13 são aditivas e offline: vínculo, origem de fatos NOVOS, solicitações V12 e decisões V13; sem backfill de legados.
- `CadastroUnicoPreflight` e `OrigemClinicaPreflight`: somente contagens e indícios, nunca prova conclusiva ou decisão automática.
- `ClassificadorEvidenciaClinicaLocal` e `DiagnosticoProntidaoEstadoVinculoService`: diagnóstico sem escrita; não alteram os indicadores oficiais.
- `VerificacaoFatoRevisaoProvenienciaService`, `SolicitacaoRevisaoProvenienciaService` e `RegistroDecisaoRevisaoProvenienciaService`: fluxo interno V12/V13, com vínculo local, autorização, justificativa, transação e auditoria. A V13 registra decisão separada; não altera a V12 ou o fato clínico.
- Fila, histórico de decisões, paginação e contadores administrativos por unidade têm testes para CAPS/USF e concorrência; não possuem interface HTTP de revisão ativada.

**Bloqueio confirmado:** a via atual V12/V13 somente admite fatos com unidade local já COMPROVADA. Fatos antigos com `unidade_origem_chave = NULL`, cadeia conflitante ou responsabilidade histórica incerta NÃO passam pelo verificador. Não remover essa proteção para habilitar o legado. Será necessário procedimento próprio de coleta de evidências e revisão da origem histórica, com escopo/autorização adequados.

## Dependências antes de qualquer corte

| Frente | Critério de entrada/saída |
|---|---|
| Evidência histórica | Definir fontes documentais suficientes para provar unidade da época; registrar decisão inconclusiva quando não houver prova |
| Permissões de investigação | Definir quem poderá analisar origem ainda não conhecida, sem permitir acesso clínico indiscriminado entre unidades |
| Conciliação de fatos | Conferir paciente, cadeia inteira de remarcações, fonte do histórico manual ou agendamento, sessões, participação e responsabilidade |
| Auditoria e transação | Registrar autor, justificativa, prova, unidade, timestamps e concorrência; separar decisão V13 da eventual aplicação clínica futura |
| Indicadores locais | Migrar com evidência `countFaltas`, última presença, status, acompanhamento, risco, pendências e gatilho de visita para cada vínculo, sem copiar indiscriminadamente o estado global do `Paciente` |
| Fluxos e exportações | Revisar `PacienteServiceImpl`, `HistoricoPacienteServiceImpl`, `RelatorioServiceImpl`, buscas, PDFs/Excel, listas e dashboards antes de substituir os filtros legados |
| Bancos e testes | Backup verificável, restauração e rollback ensaiados, validação de schema V8–V13, comparação de contagens/snapshots e preservação de auditorias Envers |

## Matriz mínima de segurança

- Casos distintos: fato novo com origem comprovada; legado `NULL`; origem contraditória; histórico manual; fonte ausente; remarcação entre unidades; grupo sem responsabilidade/participação confirmada.
- Falta de prova = nenhuma atribuição, nenhum recálculo automático e nenhuma exposição de prontuário a outra unidade.
- Comparar dois CAPS, duas USFs e uma pessoa com vínculos simultâneos; validar 401/403/404 de acordo com a convenção existente, leitura por ID, listagens, exports, paginação e concorrência.
- Decisão `APROVAR`/`REJEITAR` da V13 não deve ser confundida com aplicação de correção clínica; eventual escrita exige operação distinta, transacional, auditada e previamente aprovada.
- Ensaiar em base sintética e cópia restaurável antes de qualquer execução sobre banco persistido real; não abrir painel nem endpoint durante diagnóstico.

## Próxima sequência proposta (não executada)

1. Definir e validar o **contrato especial de revisão do legado inconclusivo**: quais provas, qual papel poderá revisá-las e como impedir acesso entre unidades.
2. Projetar o fluxo de revisão e estados de casos inconclusivos sem gravar proveniência por inferência.
3. Ensaiar auditoria, rollback, idempotência e concorrência em base de teste/cópia restaurável.
4. Planejar migração de indicadores e recálculos por vínculo, preservar regras de negócio e cobrir todos os endpoints, painéis e relatórios.
5. Somente após aceite funcional e segurança, autorizar implementação de operações clínicas e interface da aba Relatórios.

**Checkpoint concluído apenas como documentação.** A Etapa 6.3 permanece em desenvolvimento; nenhum dado histórico foi alterado.
