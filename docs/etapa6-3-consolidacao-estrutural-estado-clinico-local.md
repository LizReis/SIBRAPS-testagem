# Etapa 6.3 — consolidação estrutural do estado clínico por vínculo

## Base e escopo

Em 24/11/2026, o checkout inicial estava limpo na branch local `work`, SHA `967d5162dd543ec9005608739128423903e75e30`. A ponta remota de `correcao-cadastro-unico-isolamento` correspondia ao SHA obrigatório `518630899c0c32ecb310fa8ca97822d24cbe9600`. O desenvolvimento começou nessa branch, com a árvore limpa e sem alterações preexistentes. Os três jobs da execução de referência estavam aprovados. Os totais de referência informados foram 1.309 testes Java, 12 da matriz de concorrência e 28 Angular. [Commit-base](https://github.com/LizReis/SIBRAPS-testagem/commit/518630899c0c32ecb310fa8ca97822d24cbe9600) · [Actions #45466464385](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45466464385)

Este pacote entrega a estrutura e o ensaio interno. Os comandos continuam disponíveis exclusivamente com os perfis `test` e `ensaio-estado-local`. A projeção continua no perfil `diagnostico-migracao` e em transação de leitura. Cadastro e confirmação via CPF/CNS, consultas, busca ativa, frontend, notificações, relatórios e recálculo oficial seguem pelos fluxos existentes. Os ensaios utilizaram H2 temporário e dados sintéticos.

## Diagnóstico da base e decisões de modelagem

| Implementação anterior | Consequência | Tratamento neste pacote |
| --- | --- | --- |
| A fábrica V8 cria o vínculo com status ATIVO. A primeira revisão Envers registra esse valor. | O valor pode representar apenas a criação cadastral. | Exigir evento estruturado de inicialização na revisão de criação para reconhecer o estado inicial contemporâneo. |
| Os ensaios registravam motivos em texto de histórico. O cadastro global mantinha os últimos metadados operacionais. | O texto não comprova uma inicialização. Os campos globais não descrevem os ciclos de cada unidade. | Gravar cada inicialização, encerramento e reativação como um evento auditado associado exclusivamente ao vínculo. |
| A barreira de inicialização verificava origens isoladas por contagem. | Duas fontes externas de unidades diferentes podiam passar como se fossem prova coerente. | Conferir paciente, cadeia de agenda, sessão/grupo, participação e origem do histórico. Só ignorar para a inicialização fatos externos cuja origem seja comprovada pelos metadados existentes. |
| O vínculo tinha trava pessimista e versão. A lotação e o papel eram consultados sem trava. | A autorização podia ficar desatualizada durante o comando. | Conferir ator primeiro, travar e reler usuários em ordem estável, revalidar papel e lotação antes de escrever. |

A V14 cria `tb_evento_estado_clinico_vinculo` e sua tabela Envers. Cada evento guarda vínculo, chave da unidade, ator, horário do relógio da aplicação, natureza, status anterior e resultante, motivo e descrição complementar quando aplicáveis. A revisão de criação do vínculo contém o tipo, a referência opcional e os indicadores iniciais. Esses campos clínicos são consultados na própria revisão, evitando um segundo conjunto de valores. A sequência dos eventos fica preservada por ID e revisão Envers. O histórico textual usa o mesmo horário e segue disponível como trilha assistencial.

A migration acrescenta a chave única de referência `(id, unidade_chave)` no vínculo para a FK composta do evento. A tabela de eventos também referencia o ator e sua auditoria referencia `REVINFO`. A restrição de integridade incorpora as combinações de metadados já admitidas pelo ensaio e o trigger H2 impede alteração ou exclusão de eventos persistidos. A V14 integra o diretório já usado pelo `AgendaMigrationTool`. O Hibernate valida o esquema persistido. As tabelas são criadas vazias, sem DML clínico e sem revisões Envers artificiais. Os arquivos V1–V13 foram preservados.

## Comandos e leitura diagnóstica

- **Inicialização.** Pode integrar a transação que cria uma identidade sintética. Em identidade existente, cria somente o vínculo da unidade atual. Com o vínculo já presente, devolve o mesmo UUID sem atualizar estado, evento ou histórico. Para vínculo novo, fatos locais ou inconclusivos impedem a operação por uma resposta genérica, sem devolver registros ou contagens. Em sequência vazia comprovada no ensaio, grava ATIVO, tipo explícito, referência local opcional, zero faltas, última presença ausente, gatilho desligado, pendência falsa e risco pelo comparador existente.
- **Encerramento e reativação.** Cada comando altera o status do vínculo alvo e grava evento e histórico na mesma transação. Encerramento aceita status diferente do atual e distinto de ATIVO, com os motivos existentes e descrição obrigatória para OUTRO. Reativação exige estado local não ativo registrado e motivo. Encerrar com óbito, transferência, alta ou outro motivo afeta somente a própria unidade. Uma eventual regra transversal, inclusive para óbito, exige definição funcional.
- **Projeção.** Distingue inicialização auditada, transição auditada, status sem comprovação, ausência e conflito. Confere evento atual e sua revisão de criação, unidade e identidade do vínculo, sequência de status e coerência com a revisão atual. Um evento inserido sem a revisão Envers correspondente fica conflitante. As transições antes reconhecidas por Envers continuam reconhecidas. Um vínculo antigo com ATIVO de fábrica e nenhuma transição continua sem comprovação. Texto do histórico, referência cadastral e lotação atual ficam fora da comprovação.
- **Prontidão e plano.** A prontidão distingue valor armazenado de status comprovado e mantém as verificações de proveniência, frequência, ordem, pendência e divergência de indicadores. `PlanoIndicadoresVinculo` já exige a avaliação conclusiva de status e dos demais campos. Sua aplicação permanece desautorizada. Um status comprovado pode coexistir com indicador pendente, incompleto ou divergente.
- **Indicadores durante a transição.** Encerramento e reativação podem deixar risco, faltas, pendência, última presença e gatilho locais com o valor do instante anterior. A projeção calcula e compara sem escrita. A futura operação deverá integrar status, fatos, indicadores, histórico e auditoria na mesma transação.

### Matriz de permissões do ensaio

| Operação | Administrador local | Profissional local | Recepção | Sem autenticação ou em unidade externa |
| --- | --- | --- | --- | --- |
| Inicializar acompanhamento novo | Permitido | Permitido | Recusado | Recusado |
| Configurar tipo e referência | Permitido | Recusado pela restrição do ensaio | Recusado | Recusado |
| Encerrar | Permitido | Permitido | Recusado | Recusado |
| Reativar | Permitido | Recusado | Recusado | Recusado |

Os comandos conferem a identidade do ator e obtêm a chave da unidade pelo contexto autenticado. Encerramento, reativação e configuração resolvem o alvo por paciente, UUID do vínculo e unidade. Referência deve pertencer à unidade e ter papel de administrador ou profissional no instante do comando. Transferências posteriores não reatribuem autoria, origem nem histórico.

## Concorrência e atomicidade

- A inicialização trava a identidade e usa a unicidade paciente/unidade. Um teste simultâneo com três chamadas demonstrou uma única criação para duas chamadas da mesma USF e a criação independente na segunda USF. Cada unidade teve um evento e um histórico.
- As alterações usam trava pessimista do vínculo e versão esperada. Duas configurações com a mesma versão e a disputa entre encerramento, reativação e configuração produziram uma vencedora por versão, com estado externo preservado.
- A atualização de papel e lotação pelos fluxos de usuário participa da trava do usuário. O ensaio recarrega os registros após bloquear ator e referência. Os testes confrontam principais antigos com a nova lotação ou perfil. As rotas legadas de autorização e os dados de autoria históricos seguem seus contratos anteriores.
- Um escritor H2 sem o protocolo gravou uma falta na mesma unidade depois da verificação de ausência e antes do commit da inicialização. A projeção seguinte encontrou uma falta e divergência em relação ao zero inicial armazenado. A trava da identidade sozinha não protege esse intervalo. A ativação requer protocolo comum para agenda, grupos, históricos, correções e cadastro, incluindo revalidação do conjunto de fatos. O cenário demonstra um escritor sem o protocolo, sem inferir que todos os escritores operacionais se comportem da mesma forma.
- Nos testes, uma falha após o flush reverteu identidade, vínculo, evento e histórico. Em banco migrado, uma restrição sintética na tabela de auditoria fez o Envers falhar no commit e o snapshot confirmou a reversão de status, histórico, evento e revisões. O teste removeu a restrição sintética da cópia antes do backup.

## Migração, preservação e restauração

| Ensaio | Resultado observado |
| --- | --- |
| V13 → V14 | Uma migration executada. Todas as colunas e linhas preexistentes, incluindo vínculo, auditorias e registros V12/V13, permaneceram iguais. As duas tabelas novas ficaram vazias e a reexecução aplicou zero migrations. |
| V0 e V4 restaurados | Migração completa da fixture antiga e percurso histórico V8 → V13, seguido de V13 → V14 em cópias independentes. O inventário de proveniência, a ausência de atribuição histórica e os snapshots permaneceram coerentes. |
| Integridade do H2 migrado | FKs rejeitaram vínculo inexistente, unidade divergente, ator inexistente e revisão inexistente. DML de exclusão de evento foi recusado pelo trigger. |
| Inicialização de esquema | Testes JPA em H2 novo e em arquivo com esquema offline V14 e `ddl-auto=validate`. A tentativa de subir sobre V13 falhou na validação, com snapshot preservado, até a migração explícita. |
| Backup lógico e leitura restaurada | H2 `SCRIPT` e restauração em arquivo independente. Comparação integral de colunas e linhas, incluindo evento, histórico e revisões. A projeção reconheceu a inicialização na cópia com `ACCESS_MODE_DATA=r` e o snapshot permaneceu igual. |

As caracterizações específicas de V8, V12 e V13 usam alvos Flyway explícitos. As asserções para atingir o esquema vigente passaram a incluir V14 nos testes de migração completa. Fixtures negativas, trilhas RNF17 e mecanismos da Opção B permaneceram preservados.

## Arquivos alterados

| Arquivo | Finalidade |
| --- | --- |
| `V14__eventos_estado_clinico_por_vinculo.sql` | Tabelas, chaves, índices e proteção de imutabilidade. |
| `EventoEstadoClinicoVinculo.java` | Mapeamento JPA/Envers e invariantes dos eventos. |
| `AtoresEstadoClinicoLocalEnsaio.java` | Validação de contexto, trava ordenada e revalidação de ator e referência. |
| `InicioAcompanhamentoLocalEnsaioService.java` | Criação original, exclusão de fatos externos comprovados e persistência atômica da evidência. |
| `EstadoClinicoLocalEnsaioService.java` | Persistência de cada encerramento e reativação com o histórico local. |
| `EvidenciaStatusVinculo.java` | Leitura e verificação das revisões e dos eventos no snapshot diagnóstico. |
| `ProjecaoClinicaVinculoService.java` | Resultado explícito de comprovação e propagação de inconclusões. |
| `DiagnosticoProntidaoEstadoVinculoService.java` e `DiagnosticoConsolidadoMigracaoClinicaService.java` | Inclusão da prova de status nas avaliações local e agregada. |
| `EventoEstadoClinicoVinculoMigrationTests.java` | Esquema real V14, Opção B, FKs, rollback Envers, backup e leitura restaurada. |
| `EstadoClinicoLocalEnsaioTests.java` e `DiagnosticoProntidaoEstadoVinculoTests.java` | Matriz de comandos, escopo, legado, auditoria, concorrência, projeção, divergência e e-mail. |
| `LegacyMigrationFixture.java`, `LevantamentoOrigemLegadaTests.java` e `DiagnosticoConsolidadoMigracaoClinicaIntegrationTests.java` | Migração histórica direcionada, comparação de snapshots e diagnóstico em cópia V14 restaurada. |
| `AgendaLegacyMigrationTests.java`, `CancellationMigrationPersistenceTests.java`, `ClinicalAuditMigrationTests.java` e `GroupResponsibilityMigrationTests.java` | Preservação das caracterizações antigas e atualização dos testes que atingem o esquema vigente. |
| `PendenciaProvenienciaMigrationTests.java` e `DecisaoRevisaoProvenienciaMigrationTests.java` | Caracterizações isoladas em V12 e V13. |
| `etapa6-3-consolidacao-estrutural-estado-clinico-local.md` | Relatório de escopo, decisões, evidências e pendências. |

## Execuções

Java 21.0.13 com o compilador do JDK, Maven Wrapper 3.9.15 com settings de proxy e cache do ambiente, Node 22.23.5 e npm 11.6.2.

| Verificação | Aprovados | Falhas | Erros | Ignorados |
| --- | ---: | ---: | ---: | ---: |
| Maven focado em comandos, projeção, inventário, diagnósticos e migrações | 62 | 0 | 0 | 0 |
| Maven focado em V14 e comandos com os casos de concorrência adicionados | 15 | 0 | 0 | 0 |
| `bash ./mvnw -B -ntp clean verify` | 1.320 | 0 | 0 | 0 |
| `bash ./mvnw -B -ntp -Dtest=ReconciliationConcurrency61Characterization test` | 12 | 0 | 0 | 0 |
| Testes Angular direcionados | 28 | 0 | 0 | 0 |

O `clean verify` aprovou 116 suítes Surefire e empacotou o JAR. Os focados foram executados no diretório `backend/buscaativa` com o Maven Wrapper e `-B -ntp`:

```bash
bash ./mvnw -B -ntp -Dtest=EventoEstadoClinicoVinculoMigrationTests,EstadoClinicoLocalEnsaioTests,ProjecaoClinicaVinculoIntegrationTests,LevantamentoOrigemLegadaTests,DiagnosticoConsolidadoMigracaoClinicaIntegrationTests,AgendaLegacyMigrationTests,DecisaoRevisaoProvenienciaMigrationTests,PendenciaProvenienciaMigrationTests,ClinicalAuditMigrationTests,GroupResponsibilityMigrationTests,CancellationMigrationPersistenceTests test
bash ./mvnw -B -ntp -Dtest=EventoEstadoClinicoVinculoMigrationTests,EstadoClinicoLocalEnsaioTests test
```

No diretório `frontend/sentinela-pet`, a instalação com `npm ci` foi seguida pelos quatro testes e pelo build:

```bash
npm test -- --watch=false --include=src/app/auth/permissoes.spec.ts
npm test -- --watch=false --include=src/app/services/paciente/cadastro-unico-service.spec.ts
npm test -- --watch=false --include=src/app/pages/cadastro-paciente/cadastro-existente.spec.ts
npm test -- --watch=false --include=src/app/pages/cadastro-paciente/cadastro-paciente.spec.ts
npm run build
```

Os quatro arquivos passaram com 4, 5, 8 e 11 testes, respectivamente. A compilação de produção passou e manteve o aviso do bundle inicial de 858,72 kB para o orçamento de 500 kB. `git diff --check`, `git diff --cached --check` e `git status` foram executados na branch de desenvolvimento.

A primeira compilação encontrou um runtime Java sem compilador 21 e falhou com `release version 21 not supported`. O uso do JDK completo resolveu a execução. O primeiro focado executável aprovou 12 de 13 testes. A asserção que comparava o banco restaurado depois da V14 ainda incluía o histórico Flyway da V13 como referência. O teste passou a comparar a cópia V13 antes da migração e a cópia V14 antes e depois da leitura, preservando a verificação dos dois estágios. A instalação inicial do Node/npm usou um cache sem permissão de escrita e passou com o cache no workspace.

O workflow da branch é disparado por push com alterações de backend. [Execuções da branch](https://github.com/LizReis/SIBRAPS-testagem/actions/workflows/cadastro-unico-diagnostico.yml?query=branch%3Acorrecao-cadastro-unico-isolamento)

## Estado dos objetivos

| Objetivo do pacote | Estado |
| --- | --- |
| Estrutura aditiva offline, preservação dos dados, validação JPA/Envers e restauração sintética | Concluído |
| Inicialização explícita, identidade sintética na mesma transação, idempotência e trilha local | Concluído no ensaio |
| Encerramento, reativação e histórico de ciclos por vínculo | Concluído no ensaio |
| Reconhecimento de estados comprovados e bloqueios do legado na leitura diagnóstica | Concluído no ensaio |
| Concorrência com escritores não coordenados | Parcial. O teste reproduziu o intervalo desprotegido. Ativação operacional bloqueada. |
| Aplicação transacional dos indicadores e integração de consumidores | Pendente. Depende do protocolo comum de escrita e da revisão de autorização dos fluxos. |
| Investigação do legado inconclusivo, caso de demonstração P8 e política transversal de óbito | Pendentes de definição funcional e de evidência. |
| Ensaio em cópia de implantação e avaliação de volume | Pendente. Resultados desta entrega são de H2 sintético. |

Antes da persistência clínica oficial será necessário definir a coordenação com todos os escritores de fatos, revalidar autorização, versão, sequência e pendências na transação, aplicar os indicadores em conjunto e testar o isolamento dos consumidores. Para bases volumosas, medir o custo da leitura do histórico Envers por vínculo e das consultas de proveniência. A análise de legado deve manter a diferença entre deliberação administrativa e aplicação clínica.
