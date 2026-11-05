# Etapa 1 — correção da regressão de frequência

## 1. Objetivo, base e limites

Eliminar exclusivamente a dependência da oferta vigente na correção factual `FALTOU → PRESENTE`, mantendo colisões reais, segurança, transações e os efeitos clínicos existentes. Repositório: `LizReis/SIBRAPS-testagem`. Execução em 05/11/2026, UTC, com dados sintéticos e H2 de teste.

Branch utilizada: `correcao-fluxos-assistenciais-sibraps`. Checkout inicial limpo, exatamente em `e632e391c9a8ec3d0c03713187850ea359d1cdf4`; ancestralidade desse SHA confirmada antes da edição. Não foi criada outra branch, feito merge, reset, rebase, stash ou force push.

Referências preservadas: `correcao-estabilidade-agenda-p03-p04-p05` em `05ee61a5123dfe4e914d7c2f5f486f7ecf310164` e main em `967d5162dd543ec9005608739128423903e75e30`. O SHA do commit desta entrega e a confirmação da publicação serão informados no relatório final; o próprio documento não contém autorreferência ao seu futuro commit.

Foram lidos o [planejamento da Etapa 0](plano-etapa0-fluxos-assistenciais.md), a [revisão complementar](revisao-complementar-frequencia-concorrencia.md), a [implementação P03/P04/P05](correcao-agendamentos-p03-p04-p05.md), serviços, repositórios, entidades, controller e testes relacionados. Esses documentos anteriores são registros dos respectivos commits, inclusive do baseline reprovado anterior; não foram reescritos para ocultar a regressão.

Não foram implementadas as Etapas 2–9. Entidades, enum persistido, schema, DTOs, contratos HTTP, Angular, handler global, limites de risco e política de visita não foram alterados. As permissões continuam as atuais, inclusive a lacuna de responsabilidade específica a tratar na Etapa 3.

## 2. Causa raiz

`SITUACOES_ATIVAS` continua contendo `AGENDADO`, `REMARCADO` e `PRESENTE`. `FALTOU` não ocupa vaga. O ingresso genérico de situação não ativa para ativa em `atualizarStatus()` chamava `validarVaga()`, que verificava tanto colisões reais quanto grade, bloqueio, disponibilidade e capacidade vigentes.

Assim, corrigir uma ausência para presença era tratado como adquirir uma reserva. Uma mudança posterior na oferta podia impedir registrar corretamente o que ocorreu, ainda que não houvesse outro atendimento no mesmo instante. A transação rejeitada não recalculava indicadores nem atualizava histórico/gatilho: havia atomicidade, mas o fato errado permanecia.

## 3. Comportamento anterior e regressão reproduzida

A revisão complementar anterior documentou que a versão anterior a P04 permitia correções históricas independentemente da oferta; P04 acrescentou a validação completa para ingresso ativo. Nesta etapa não foi necessário alterar ou executar um checkout da main: o baseline foi reproduzido na branch obrigatória ainda no commit `e632e39`.

`bash ./mvnw test` completo, com ambiente herdado, reproduziu **289 executados, 280 aprovados, 9 falhas, 0 erros e 0 ignorados — BUILD FAILURE**. Término: 19:35:56 UTC; duração Maven 38,332 s. As oito invocações de `historicalAbsenceCorrectionMustSurviveLaterScheduleChanges` com oferta removida/zerada/reduzida/bloqueada, para administrador e profissional, esperavam 200 e receberam 409. O nono caso, `correctingReplacementAttendanceMustClearVisitEvenAfterCapacityWasClosed`, também recebeu 409; seu log mostrou `visit=true absences=1`.

Os dois casos com oferta disponível e os dez casos de correção inversa já passavam. A correção não torna livre a reativação de reservas, nem altera a guarda temporal genérica preexistente.

## 4. Regra aprovada e comportamento resultante

A frequência factual de um atendimento registrado pode ser corrigida sem depender automaticamente da oferta atual. A correção continua proibida quando resultar em colisão ativa do mesmo paciente ou profissional na mesma data/hora. Conflito real exige regularização futura; não se altera automaticamente o registro concorrente.

| Cenário | Antes desta etapa | Depois desta etapa |
|---|---|---|
| FALTOU → PRESENTE sem colisão, oferta removida/zerada/reduzida/bloqueada | 409 | 200, com recálculo e histórico |
| FALTOU → PRESENTE histórico fora da grade atual alterada | 422 por grade | 200 se as demais regras forem válidas |
| FALTOU → PRESENTE com outro ativo do paciente/profissional no mesmo instante | Rejeitado | Continua 409, sem alteração parcial |
| PRESENTE → FALTOU | Permitido por perfil/escopo, sem oferta | Preservado |
| Novo agendamento/remarcação fora da grade | Rejeitado | Preservado |
| CANCELADO → AGENDADO sem oferta válida | Rejeitado | Preservado |
| CANCELADO/REMARCADO_ORIGEM → PRESENTE/FALTOU | 422 | Preservado; regularização é Etapa 5 |
| Frequência de data futura | 422 | Preservado |
| Correção pela recepção / outra unidade | 422 / 404 | Preservado |

O tratamento factual vale para a correção de hoje ou do passado que já passa pela guarda temporal existente. Não foi implementada a futura exigência de aguardar a hora prevista no mesmo dia, nem alterada a política temporal de reativação.

## 5. Arquivos modificados

| Arquivo | Alteração |
|---|---|
| `backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/AgendamentoServiceImpl.java` | Extrai colisões de `validarVaga()` para método compartilhado e direciona apenas FALTOU → PRESENTE à validação sem oferta/grade. |
| `backend/buscaativa/src/test/java/com/pet/buscaativa/HistoricalAttendanceReviewTests.java` | Mantém 33 casos; atualiza quatro invocações de caracterização conforme regra aprovada e fortalece o caso da visita com oferta fechada. |
| `backend/buscaativa/src/test/java/com/pet/buscaativa/AttendanceCorrectionIntegrityTests.java` | Acrescenta 45 casos de colisão, permissões atuais, indicadores combinados, rollback e concorrência real. |
| `docs/etapa1-correcao-regressao-frequencia.md` | Este relatório. |

Não foi necessário modificar `AgendamentoRepository`, os locks de paciente/usuário, o controller, `PacienteServiceImpl` ou `HistoricoPacienteServiceImpl`. Fora desses arquivos, o conteúdo anterior deve permanecer idêntico; a conferência final usa diff e hashes dos arquivos rastreados capturados antes das alterações.

## 6. Separação das validações e matriz de transições

`validarVaga()` conserva a grade e todas as verificações da reserva na mesma ordem lógica. A parte de colisões foi extraída para `validarConflitosDeHorario()`, usando as mesmas consultas e o mesmo conjunto ativo. O parâmetro de contexto desse método apenas acrescenta orientação de regularização à mensagem da correção; não modifica a definição de conflito.

Depois das verificações preexistentes de acesso, data, versão, idempotência e permissões, `atualizarStatus()` distingue:

```java
if (statusAnterior == SituacaoAtendimento.FALTOU && novoStatus == SituacaoAtendimento.PRESENTE) {
    validarConflitosDeHorario(profissionalBloqueado, pacienteBloqueado,
            agendamento.getDataAgendamento(), agendamento.getHoraAtendimento(), true);
} else if (SITUACOES_ATIVAS.contains(novoStatus) && !SITUACOES_ATIVAS.contains(statusAnterior)) {
    validarVaga(profissionalBloqueado, pacienteBloqueado, agendamento.getDataAgendamento(),
            agendamento.getTurnoAgendamento(), agendamento.getHoraAtendimento());
}
```

A mudança de status e os efeitos clínicos só ocorrem depois dessas validações. Nenhum agendamento novo é criado pela correção e seus paciente, profissional, data, hora e turno permanecem os mesmos.

| Transição | Verifica oferta atual? | Verifica colisões? | Recalcula indicadores? | Regra |
|---|---|---|---|---|
| Correção factual FALTOU → PRESENTE | Não; também não aplica grade vigente | Sim, paciente e profissional, sob locks | Sim, na mesma transação | RN1-01/02/03/04/05/06/09 |
| Correção inversa PRESENTE → FALTOU | Não | Não adquire nova ocupação | Sim | RN1-08, lógica existente |
| Registro inicial AGENDADO/REMARCADO → PRESENTE/FALTOU | Não, como antes | Não revalida; origem já ativa | Sim | Permissões e comportamento existentes preservados |
| Nova reserva → AGENDADO, via POST | Sim, incluindo grade/turno | Sim, paciente e profissional | Não registra frequência nem chama o recálculo nessa operação | P03/P04 preservados |
| Remarcação, via POST | Sim para a nova reserva | Sim; também valida mesmo paciente e estado/vínculo do original | O fluxo de save permanece sem novo recálculo | P03/P04/P05 preservados |
| Reativação CANCELADO → AGENDADO | Sim, incluindo grade/turno | Sim | Sim, pelo caminho de status existente | Oferta e guarda temporal mantidas; sem novo fluxo da Etapa 5 |
| CANCELADO/REMARCADO_ORIGEM → frequência | Não alcança essa validação | Operação já rejeitada com 422 | Não | Não regulariza cancelamentos nesta etapa |

Não se afirma que o registro inicial de frequência recebeu novas verificações: ele já parte de um estado ativo. Inconsistências históricas previamente existentes exigem a futura reconciliação, não uma ampliação silenciosa deste patch.

## 7. Detecção de colisões e exclusão do próprio registro

Consultas reutilizadas: `existsAgendamentoAtivoNoMesmoHorario()` para profissional e `existsAtivoDoPacienteNoMesmoHorario()` para paciente. Comparam a mesma data e hora exata e consideram apenas `AGENDADO`, `REMARCADO`, `PRESENTE`. O turno não cria uma exceção para colisões no mesmo instante.

A chamada ocorre **antes** de mudar o registro corrente de FALTOU para PRESENTE. Esse registro ainda é não ativo e, portanto, não satisfaz o filtro da própria consulta. Novos agendamentos ainda não foram persistidos quando validados; outras entradas em ativo também partem de status não ativo. Pedidos com status já igual retornam antes dessa validação. Por isso, não foi necessária consulta nova para excluir ID explicitamente. Se futuramente esse helper passar a validar uma origem ativa, essa premissa deverá ser revista.

Colisão retorna `ConflictException`/HTTP 409 com mensagem de horário ocupado e orientação: “Regularize o conflito antes de corrigir a frequência.” Não divulga nome, ID ou detalhes do outro atendimento. Não consulta oferta como condição factual, não corrige o concorrente e não cria/exclui registros para contornar a rejeição.

Os testes preparam inconsistências históricas por fixtures persistidas controladas, sem tentar furar o POST. Cobrimos cada um dos três status ativos para colisão do paciente e do profissional, além dos três não ocupantes, que continuam não sendo tratados como reservas ativas.

## 8. Estratégia transacional e ausência de antecipação arquitetural

Foram mantidos `@Transactional` e a ordem **paciente → profissional → agendamento**. A identidade é lida como projeção escalar antes dos locks, evitando carregar uma entidade de agendamento obsoleta antes da espera. O atendimento é lido bloqueado, a versão esperada é validada e a frequência só é alterada após a checagem de colisão.

Correção e criação/remarcação/status do mesmo paciente disputam o lock desse paciente; pacientes diferentes com mesmo profissional disputam o lock do profissional. A leitura de colisão e a escrita ocorrem com esses locks mantidos até commit/rollback. Não se introduziu `synchronized`, retry automático ou uma nova ordem de aquisição. A extração não acrescenta locks de outros pacientes ou profissionais.

P04 permanece protegido nos caminhos coordenados do serviço: duas operações sobre um mesmo instante não podem ambas adquirir ocupação conflitante. Falha técnica de lock/timeout não é evidência de rejeição funcional: os testes falham se surgir a resposta técnica genérica, exceção inesperada ou resultado persistido incorreto. O cenário de versão esperada testa separadamente o 409 otimista deliberado, não o confunde com colisão.

Além das rejeições prévias à escrita, um teste provoca falha sintética no serviço de histórico depois da mudança e do recálculo. Confere rollback de status/versão, indicadores, registros e conteúdo do histórico. O spy existe somente no teste; nenhum mecanismo de falha foi introduzido em produção.

## 9. Indicadores e efeitos em cascata comprovados

O recálculo existente foi preservado: consulta fontes assistenciais, ordena eventos cronologicamente, recalcula sequência de faltas, última presença e risco; não decrementa contador manualmente nem usa o dia da correção como última presença.

| Fixture / operação | Resultado persistido verificado |
|---|---|
| Falta de dez dias atrás corrigida para presença; três faltas anteriores e uma presença anterior | Faltas consecutivas 0; última presença na data do atendimento corrigido; risco VERDE; mesmo evento atualizado para PRESENCA. Totais do histórico: duas presenças e três faltas. |
| Correção inversa na mesma fixture | Quatro faltas consecutivas, última presença anterior, risco VERMELHO. Mantém o ID do evento de frequência e cria o evento legítimo separado de entrada em vermelho. |
| Remarcação após falta, nova falta, capacidade depois zerada, correção para presença | HTTP 200, PRESENTE, faltas 0, última presença na data da remarcação, VERDE, gatilho desativado; nenhuma nova consulta e nenhum evento de frequência duplicado. Log após correção: `visit=false absences=0`. |
| Presença de grupo anterior ao atendimento corrigido, grupo/individual com faltas posteriores àquela presença | Correção do atendimento posterior deixa faltas 0 e última presença na data corrigida; VERDE. |
| Presença de grupo posterior ao atendimento corrigido, seguida de falta no grupo e falta individual | Mantém a presença mais recente do grupo, duas faltas consecutivas e AMARELO. Não substitui última presença posterior por uma data mais antiga. |
| Repetir o mesmo status | Mesmo estado persistido, versão/contagens/eventos estáveis, sem dupla contagem. |
| Colisão real, versão inválida, falta de permissão ou falha tardia no histórico | Sem mudança parcial de atendimento/indicadores/histórico; outro atendimento também intacto. |

As duas fixtures mistas usam paciente AMBOS, sessões REALIZADA e frequência de grupo efetivamente persistida; não simulam os indicadores por mocks. Os limites existentes de risco e a política de visita não foram reescritos.

## 10. Preservação do histórico e das permissões atuais

`HistoricoPacienteServiceImpl.registrarAlteracaoDeAtendimento()` continua atualizando o evento de frequência existente quando corrige PRESENTE/FALTOU. As asserções conferem ID, tipo, situação, totais e ausência de duplicação. O evento separado de mudança para risco vermelho continua permitido; não é duplicação de presença/falta.

Isto não implementa auditoria detalhada de todas as correções, justificativa ou evento imutável por alteração. O profissional atribuído ao evento e a auditoria técnica existentes não foram substituídos por um modelo novo.

Administrador e profissional continuam autorizados conforme regras atuais; recepção continua vedada para correção PRESENTE ↔ FALTOU e mantém registro operacional inicial. Outra unidade continua recusada, inclusive administrador. Versão esperada inválida e data futura continuam recusadas. Um teste positivo de colega da mesma unidade explicita que não se antecipou a futura restrição ao responsável específico.

## 11. Testes de regressão e expectativas atualizadas

Nenhum teste foi removido, ignorado ou desabilitado. Os 289 cenários anteriores continuam presentes; apenas as quatro invocações de caracterização cuja política foi expressamente aprovada tiveram expectativa alterada.

### Nove falhas anteriores

`historicalAbsenceCorrectionMustSurviveLaterScheduleChanges`: continuam os dez casos por perfil/oferta, mantendo a expectativa 200 original. Agora passam os oito anteriormente reprovados: índices `[2]/[7]` REMOVED, `[3]/[8]` ZERO_CAPACITY, `[4]/[9]` REDUCED_CAPACITY e `[5]/[10]` BLOCKED, respectivamente administrador/profissional. `correctingReplacementAttendanceMustClearVisitEvenAfterCapacityWasClosed` mantém sua expectativa 200 e ganhou verificações de última presença, risco, contagens de registros/eventos, ID/tipo/situação do histórico e repetição idempotente.

### Quatro invocações de caracterização

`currentBlockedHistoricalCorrectionIsAtomicButRequiresBusinessDecision` foi renomeado para `historicalCorrectionIgnoresChangedOfferAndPersistsAllClinicalEffects`, conservando os mesmos quatro cenários REMOVED/ZERO_CAPACITY/REDUCED_CAPACITY/BLOCKED.

| Asserção anterior | Asserção atual | Motivo |
|---|---|---|
| Resposta 409 | Resposta 200 | RN1-02 aprovada: mudança posterior de oferta sem colisão não impede corrigir o fato. |
| Snapshot inteiro igual antes/depois | Quantidade de agendamentos e histórico igual; versão incrementada exatamente uma vez; ID do evento preservado | A alteração factual legítima deve ser persistida, não permanecer bloqueada. |
| Indicadores implícitos no snapshot inalterado | `checkIndicators()` confere PRESENTE, zero faltas, última presença, VERDE, evento e totais clínicos corretos | Fortalece efeitos de sucesso, sem apagar a cobertura de estado persistido. |

A cobertura de atomicidade das rejeições foi mantida nos novos seis cenários de colisão, em permissão/versão e na falha tardia; não se trocou rollback por asserção trivial. `HistoricalAttendanceReviewTests` continua com 33 casos.

### Matriz E1-01 a E1-16

| ID | Cobertura / resultado |
|---|---|
| E1-01 | ZERO_CAPACITY, dois perfis e caracterização: 200 sem colisão. |
| E1-02 | REMOVED, dois perfis e caracterização: 200. |
| E1-03 | BLOCKED, dois perfis e caracterização: 200. |
| E1-04 | REDUCED_CAPACITY, dois perfis e caracterização: 200 mesmo com ocupação do turno acima da nova capacidade em outros horários. |
| E1-05 | Mesmo paciente/instante, profissionais diferentes, cada status ativo: 409 e banco intacto. |
| E1-06 | Mesmo profissional/instante, pacientes diferentes, cada status ativo: 409 e banco intacto. |
| E1-07 | Mesmo paciente em horários diferentes: ambas as correções aceitas. |
| E1-08 | Pacientes e profissionais diferentes: duas correções concorrentes confirmadas. |
| E1-09 | Comparação de snapshots, identidades/versões e conteúdo de eventos após colisão; teste adicional de falha tardia com rollback integral. |
| E1-10 | Correção versus POST do mesmo paciente/horário: uma confirmação e uma rejeição; três repetições. |
| E1-11 | Correções do mesmo paciente/horário com profissionais diferentes: uma confirmação e uma rejeição; três repetições. |
| E1-12 | Correção versus remarcação do mesmo original: uma confirmação e uma rejeição; três repetições e verificação de original/substituto. |
| E1-13 | Intervalo da grade alterado de 30 para 45 no teste: 08:00 histórico corrigido com 200; nova reserva 08:00 rejeitada com 422. Configuração restaurada em finally, sem alterar os horários oficiais. |
| E1-14 | Cinco casos de reativação existentes: disponível 200; removida/zerada/reduzida/bloqueada 409. |
| E1-15 | `AgendaGridTests`, configuração e integração preservadas: novo agendamento fora da grade recusado. |
| E1-16 | `AgendaRescheduleTests` e integração preservadas: paciente diferente do original recusado sem alteração parcial. |

Os 45 novos casos também cobrem vizinhos não ativos, permissão operacional da recepção no registro inicial, colega na unidade pela política atual, unidade externa, versão, futuro, cancelado/substituído, grupos e correções concorrentes com mesmo profissional ou versão.

## 12. Evidências de concorrência

Reutilizado `AgendaIntegritySupport.race()`: threads diferentes, transações `REQUIRES_NEW`, pool suficiente, `CountDownLatch` de prontidão/início, conexões H2 distintas verificadas por `SELECT SESSION_ID()`, espera limitada e verificação de término. O latch só libera as operações depois que todas possuem conexão. Rejeições marcam rollback. Não há uma transação única envolvendo todas as requisições.

Os novos testes verificam também o título de erro 409 sem versão esperada: deve ser conflito **de negócio**, além de recusar a mensagem genérica de contenção. O teste de versão esperada admite o conflito otimista especificamente previsto. Asserts de estado rodam depois do término/commit dos participantes.

Resultado da execução completa `full-test`; os números abaixo incluem apenas as novas disputas E1:

| Disputa | Grupos | Operações | Confirmações | Rejeições | Estado final conferido |
|---|---:|---:|---:|---:|---|
| Correção × criação, mesmo paciente/horário | 3 | 6 | 3 | 3 | Uma ocupação ativa; linha/evento de nova reserva só se o POST vencer. |
| Duas correções, mesmo paciente/horário, profissionais diferentes | 3 | 6 | 3 | 3 | Uma presença ativa; outra falta intacta; sem novos registros/eventos. |
| Duas correções, mesmo profissional/horário, pacientes diferentes | 3 | 6 | 3 | 3 | Uma presença ativa no horário do profissional; uma falta permanece; indicadores coerentes. |
| Correção × remarcação | 3 | 6 | 3 | 3 | Original PRESENTE sem substituto, ou REMARCADO_ORIGEM com um único substituto REMARCADO. |
| Correção × reativação de outro registro | 3 | 6 | 3 | 3 | Uma ocupação ativa, sem novos agendamentos. |
| Duas correções da mesma versão | 3 | 6 | 3 | 3 | Um incremento de versão e um único fato atualizado. |
| Correções independentes | 1 | 2 | 2 | 0 | Uma presença por paciente/profissional, sem conflito nem novos agendamentos. |
| **Novas disputas** | **19** | **38** | **20** | **18** | **Sem duplicidade ativa ou gravação parcial nas verificações.** |

Incluindo as disputas preexistentes P03/P04/P05: **40 grupos, 100 operações, 43 confirmações e 57 rejeições** em `full-test`; `verify-clean` e `verify-dev` também apresentaram essa distribuição. Nas três execuções completas são 300 operações, 129 confirmações e 171 rejeições esperadas, com estado final validado. A disputa preexistente cancelamento/criação admite serializações com uma ou duas confirmações, por isso o agregado pode variar entre execuções corretas; não se usa um total fixo artificial de confirmações para essa disputa.

Não houve deadlock, timeout técnico ou falha transacional inesperada nas disputas aprovadas. Isto é uma observação das execuções H2, não garantia universal de ausência de contenção. Os testes globais de handler injetam falhas técnicas deliberadamente e são um conjunto distinto; não contam como rejeições funcionais de corrida.

## 13. Execução, ambiente e resultados completos

Com Java 21, a partir de `backend/buscaativa`, foram executados os alvos `test` e `verify` pelo Maven Wrapper, sem `-DskipTests` ou filtro na suíte completa. As opções adicionais foram somente batch mode, settings de acesso ao repositório de dependências e cache do ambiente:

```bash
bash ./mvnw -s /workspace/.cache/sibraps/maven-settings.xml -B \
  -Dmaven.repo.local=/workspace/.cache/sibraps/m2/repository test
bash ./mvnw -s /workspace/.cache/sibraps/maven-settings.xml -B \
  -Dmaven.repo.local=/workspace/.cache/sibraps/m2/repository verify
```

O teste focado usou `-Dtest=HistoricalAttendanceReviewTests,AttendanceCorrectionIntegrityTests`; depois toda a suíte foi executada sem esse filtro. Logs e XMLs foram separados por execução em `/tmp/sibraps-etapa1/`, fora do commit. `target/` permanece ignorado. Esses caminhos temporários não são um armazenamento permanente de evidências.

`baseline` e `full-test` mantiveram as variáveis herdadas. `verify-clean` representa o cenário solicitado sem variáveis externas de desenvolvimento. `verify-dev` representa o cenário com `SPRING_PROFILES_ACTIVE=dev`, usuário/senha de dev sintéticos e JWT Base64 aleatório de 256 bits; esses valores não são impressos ou versionados. Não foram retiradas as variáveis de dev dessa execução para forçar aprovação. O isolamento é o já existente em `SecurityTestSupport` e nos runners de configuração, sem alteração nesses arquivos.

| Conjunto | Executados | Aprovados | Reprovados | Ignorados | Resultado |
|---|---:|---:|---:|---:|---|
| Baseline `test`, antes do patch | 289 | 280 | 9 | 0 | BUILD FAILURE, saída 1; 0 erros |
| Focado frequência + integridade | 78 | 78 | 0 | 0 | BUILD SUCCESS; 0 erros |
| Completo `test`, ambiente herdado | 334 | 334 | 0 | 0 | BUILD SUCCESS; 0 erros |
| Completo `verify`, sem variáveis de dev | 334 | 334 | 0 | 0 | BUILD SUCCESS; 0 erros |
| Completo `verify`, perfil dev e valores sintéticos presentes | 334 | 334 | 0 | 0 | BUILD SUCCESS; 0 erros |

Término/duração Maven: `full-test` às 19:42:39 UTC / 45,951 s; `verify-clean` às 19:43:57 UTC / 42,756 s; `verify-dev` às 19:45:19 UTC / 38,642 s. Ambos os `verify` concluíram também o empacotamento/repackage, não apenas a fase de teste. Os três comandos completos retornaram código zero.

Não somar as execuções repetidas como casos únicos: são **334 casos por execução completa**, sendo **289 anteriores e 45 novos**. Não houve exclusão/ignorado para produzir o verde.

Distribuição dos casos na execução completa:

| Conjunto | Executados | Aprovados | Reprovados | Ignorados | Resultado |
|---|---:|---:|---:|---:|---|
| P01/P02: JWT/configuração/política/autorização/H2 | 35 | 35 | 0 | 0 | Aprovado |
| P03: grade e configuração | 36 | 36 | 0 | 0 | Aprovado |
| P04: concorrência existente | 18 | 18 | 0 | 0 | Aprovado |
| P05: remarcação | 10 | 10 | 0 | 0 | Aprovado |
| Integração P03/P04/P05 | 4 | 4 | 0 | 0 | Aprovado |
| Frequência histórica existente | 33 | 33 | 0 | 0 | Inclui os nove anteriormente reprovados e quatro caracterizações atualizadas |
| Novos testes de integridade da correção | 45 | 45 | 0 | 0 | Aprovado |
| Handler global, sem alteração de produção | 25 | 25 | 0 | 0 | Caracterização aprovada; política global ainda pendente da Etapa 7 |
| Isolamento/unidades/profissionais/histórico | 127 | 127 | 0 | 0 | Aprovado |
| Inicialização | 1 | 1 | 0 | 0 | Aprovado |
| **Total** | **334** | **334** | **0** | **0** | **Aprovado localmente** |

Isolamento/unidades inclui `IsolamentoUnidadeTests` (65), `ProfissionaisEscopoTests` (47), `UsuarioUnidadeTests` (13), `MigracaoUnidadeTests` (1), `PainelHistoricoEscopoTests` (1). P01/P02 inclui 14 de configuração de chave, 2 de política, 15 de segurança conjunta e 4 de perfis H2. Os 231 anteriores à revisão complementar e os 58 da revisão continuam presentes.

O workflow existente não foi alterado nem acionado; push da branch de trabalho não é gatilho automático de push/main. Não se afirma aprovação de CI remota ou execução de suíte Angular nesta etapa backend.

## 14. Outros caminhos de escrita inspecionados

| Caminho | Constatação / encaminhamento |
|---|---|
| `AgendamentoController` → `save()` | POST de nova reserva/remarcação mantém validação completa. `save()` controla AGENDADO/REMARCADO/REMARCADO_ORIGEM, não aceita um resultado factual arbitrário do DTO. |
| `AgendamentoController` → `atualizarStatus()` | Única entrada HTTP localizada para mudar situação do agendamento individual. Mantém parâmetros e guarda genérica de data/versão/perfil; não se implementou uma matriz completa nova de transições. |
| `AgendamentoMapper.updateAgendamentoFromDTO()` | Declaração encontrada, sem chamada em produção localizada. Não foi conectada a uma rota nem alterada. Uso futuro precisará respeitar validações/locks. |
| `DevConfig` | Fixtures de desenvolvimento gravam agendamentos diretamente, inclusive exemplos de remarcação; não passam pelo serviço operacional. Nenhuma alteração aqui. Não usar esse mecanismo para gravação clínica/importação. |
| `HistoricoPacienteServiceImpl` | Altera situação do evento de histórico, não o status canônico do agendamento por um segundo caminho. Registros manuais de histórico não são nova API de correção individual. |
| `GrupoTerapeuticoServiceImpl` | Registro, confirmação, inscrição retroativa e correção de frequência de grupo usam modelo de sessão/participante próprio e alimentam o mesmo recálculo do paciente. Não reescritos; integração de indicadores coberta pelas fixtures mistas e suíte existente. |
| SQL/importações ou escrita direta por repositório | Não passam automaticamente pelas barreiras de serviço. Não foram introduzidos nem regularizados nesta etapa. |

Riscos adicionais mantidos fora do escopo: o PATCH genérico não tem ainda a responsabilidade clínica/justificativa da Etapa 3; frequência inicial em registro já ativo não ganhou validação retroativa de inconsistências; remarcação substitui a situação do original e o fluxo de `save()` não recalcula assiduidade imediatamente. Esse acoplamento do modelo legado deve ser avaliado nas Etapas 2/4/6, sem inferir uma nova regra neste patch. Grupos têm sua própria ordem de locks e operações: não se declara auditoria exaustiva de concorrência entre todos os módulos a partir dos testes de agenda.

## 15. Limitações, riscos restantes e Etapas 2–9

- **Etapa 2:** separar reserva/frequência com compatibilidade e migração controlada. Este patch mantém o enum único e PRESENTE como ocupante de vaga; não resolve ambiguidades do legado.
- **Etapa 3:** responsável específico, justificativa e auditoria detalhada continuam pendentes. A permissão de colega da mesma unidade é preservada e explicitamente caracterizada, não apresentada como solução definitiva.
- **Etapa 4:** consolidar fluxo temporal e fonte oficial de horário/fuso; hoje a guarda compara data, não hora prevista.
- **Etapa 5:** regularizar cancelamento incorreto e distinguir reserva futura do fato ocorrido. Continua a proibição atual de frequência em cancelado/substituído e a política temporal genérica de reativação.
- **Etapa 6:** reconciliação autorizada de colisões históricas. Por enquanto, colisão real é rejeitada integralmente; a mensagem não implica que já exista uma tela/rota de reconciliação.
- **Etapa 7:** rever classificação global de locks/infraestrutura e observabilidade. O handler atual permaneceu intacto; os testes novos não aceitam falha técnica como prova de proteção funcional.
- **Etapa 8:** adaptar Angular, inclusive mensagem que hoje interpreta todo 409 como edição por outra pessoa. Não houve alteração de frontend nesta etapa.
- **Etapa 9:** validação integrada/CI e preparação operacional; confirmar concorrência/migração no SGBD de implantação. Não há validação de PostgreSQL/MySQL ou produção nesta entrega.

P01/P02 e P03/P04/P05 permanecem exercitados pela suíte completa. A garantia local observada usa H2 e os fluxos coordenados da aplicação; não cobre SQL direto, todas as importações ou todo banco futuro. Nenhuma afirmação de prontidão para produção decorre do build local verde.

## 16. Critérios de revisão e fechamento

Antes do commit/publicação: conferir diff completo, `git diff --check`, índice nominal de quatro arquivos, hashes dos demais arquivos anteriores, branch/ancestralidade, resultados integrais e heads remotos. Commit deve conter só serviço, teste existente, novo teste e este documento. Publicação somente por push normal da branch de trabalho, sem PR/merge.

As evidências acima recuperam as nove regressões mantendo proteção contra colisões, indicadores, histórico e permissões atuais, incluindo `verify-dev` aprovado. **APROVADA PARA REVISÃO** quanto à implementação e validação local da Etapa 1. O relatório final registra as conferências do commit/publicação e a preservação das referências; esta classificação não antecipa as Etapas 2–9 nem declara prontidão para produção.
