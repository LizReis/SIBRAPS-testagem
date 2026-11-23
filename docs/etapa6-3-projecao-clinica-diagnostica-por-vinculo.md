# Etapa 6.3 — projeção clínica diagnóstica por vínculo

## Base e entrega

A branch `correcao-cadastro-unico-isolamento` estava limpa em `4577729`. O remoto apontava para `ee8892049700c70249cdfbd54dd45a0f293fda63`. O diff trouxe a validação estrutural das remarcações de `222f600` e as correções de fixtures de `afe9871` e `ee88920`. A atualização foi fast-forward e a ancestralidade dos três commits foi confirmada. A referência registrou 1.271 testes de backend e 12 de concorrência aprovados, além dos jobs Angular aprovados. [Actions da base #45287011683](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45287011683)

O SHA final de código e testes é `743a29185df66caba76d9d61f8cae9bb5d314337`. O commit de documentação da mesma branch registra os resultados desse SHA. [Commit validado](https://github.com/LizReis/SIBRAPS-testagem/commit/743a29185df66caba76d9d61f8cae9bb5d314337) · [Actions #45291780594](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45291780594) · [Artefato de testes Maven](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45291780594/artifacts/14874243788)

## Execução e significado do resultado

No diretório `backend/buscaativa`, com Java 21:

```bash
bash ./mvnw -B -ntp -Dtest=ProjecaoClinicaVinculoIntegrationTests,ProjecaoRiscoPoliticaLegadaTests test
```

O perfil `diagnostico-migracao` disponibiliza `ProjecaoClinicaVinculoService.projetar(pacienteId, vinculoId, dataReferencia)` para o executor interno. O contexto autenticado determina a unidade no servidor. A seleção por vínculo, paciente e unidade ocorre em uma consulta restrita, antes de carregar o cadastro ou buscar fatos assistenciais. A leitura transcorre em transação própria, serializável e de somente leitura. O ensaio restaurado usa `CorrectiveMigrationApplication`, H2 com `ACCESS_MODE_DATA=r` e adaptador SUS simulado. A data explícita governa os limiares de risco sobre o snapshot efetivamente lido. Uma data diferente permite reavaliar os dias sem presença sobre os mesmos fatos. A reconstrução de um estado passado, anterior a uma correção já gravada, exige um próprio ensaio de histórico Envers.

Cada avaliação informa o valor, as causas de inconclusão e as comparações com o estado local armazenado e com a referência global armazenada. `COINCIDE`, `DIVERGE`, `SEM_REFERENCIA` e `INCONCLUSIVA` são sinais de comparação. A referência global entra após o cálculo local e o resultado traz só o sinal dessa comparação. Sequência vazia comprovada retorna zero e pode confirmar ausência de última presença. Uma sequência indeterminada mantém as causas explícitas. A lista de frequências preserva fatos pelo par fonte e ID. A ordem de apresentação é determinística. Presença e falta no mesmo instante continuam visíveis, e o resultado conclusivo aguarda conferência.

O status inicial `ATIVO` das fábricas é uma hipótese, enquanto uma transição Envers do vínculo, com última revisão coerente com o valor atual, é o critério técnico usado no ensaio para confirmar o estado. Essa evidência registra a alteração local. A suficiência de evidência para atribuir estado histórico ao legado é uma decisão funcional. O risco do status confirmado `INATIVO` segue o retorno antecipado verde da política atual. Status ou tipo ausente e pendências impedem concluir o risco nos casos que precisam desses insumos. O gatilho RF22 usa os fatos e o estado de pendência local e permanece independente da classificação.

## Matriz de consumidores e dependências

| Dimensão | Política ou consumidor de referência | Insumo local e integração | Evidência executada | Divergência observada ou dependência para o corte |
| --- | --- | --- | --- | --- |
| Identidade e agenda | `AutorizacaoVinculoAssistencialService`, `ConsultaAgendamentosClinicosLocaisService` e `/api/pacientes/{id}/agendamentos-locais` | Integra o vínculo explícito, a origem da reserva e o avaliador compartilhado de cadeia. | Quatro vínculos simultâneos, cruzamentos por ID, paciente incorreto, autenticação ausente e CAPS de referência sem vínculo. Fatos de cancelamento com e sem frequência conservam sentidos diferentes. | As rotas legadas de agenda e a autorização de comandos permanecem sujeitas ao corte. |
| Grupos e faltas | `LinhaDoTempoFrequenciasVinculoService`, `InventarioFrequenciasVinculoService`, `DiagnosticoFaltasConsecutivasVinculoService`, `GrupoTerapeuticoServiceImpl`, `PacienteServiceImpl.recalcularAssiduidadePaciente` | Integra participação do paciente, sessão realizada com data, responsabilidade registrada e frequência conclusiva, junto com frequência individual canônica. | USF com sequência mista, CAPS com empate, grupo sem responsável, sessão agendada e cancelada e correção retroativa. Regressão cobre estado do antecessor, ordem, elo ausente, ramificação, ciclo e origem externa ou desconhecida. | A contagem global armazenada de 8 e as locais de 5, 0 e 2 têm diferenças esperadas por escopo. O legado pode limpar pendência antiga após presença posterior; a projeção conserva a barreira por origem desconhecida. Requer decisão clínica antes de qualquer promoção. |
| Histórico | `ConsultaHistoricoClinicoLocalService`, `/api/pacientes/{id}/historico-local` e `HistoricoPacienteServiceImpl` | Eventos da unidade com fonte coerente ou ação local admitida, com fonte, ID, tipo e ordem. | Dois espelhos do mesmo agendamento permanecem no histórico e o fato conta uma vez. Ação local e evento de sessão comprovada aparecem somente em seus vínculos. | O histórico legado agrega pela identidade e lê estado global. Para o corte, migrar a consulta, os DTOs e as regras de detalhe e autoria. |
| Estado e risco | `PacienteServiceImpl.calcularEAtualizarRisco` e `ComparadorPoliticaRiscoVinculo` | Integra estado e indicadores do vínculo, prova técnica Envers e data explícita. | 24 comparações com a política real em entidade transitória. Limites estritos de 15/30 para grupo e AMBOS e de 60/120 para individual, mais 2 faltas para amarelo e 3 para vermelho. Cobertura de inatividade, última presença ausente e data de referência alternativa no mesmo conjunto de fatos. | O CAPS AD calculou amarelo com a referência local e global armazenadas em vermelho. Defaults de vínculo e prova histórica de status exigem validação funcional. |
| RF22, busca ativa e RNF17 | `PoliticaVisitaDomiciliar`, comparadores locais, `/api/pacientes/busca-ativa` e `AuditoriaBuscaAtivaController` | Integra visita, risco e motivos com prioridade de revisão, risco vermelho e visita. A composição guarda indisponibilidade pelos respectivos motivos. | Dois casos positivos de visita e presença posterior que remove o gatilho. O formatador existente recebeu, na fixture, endereço fixo, rua com referência, rua sem referência e revisão histórica. Testes RNF17 conferiram os contratos existentes. Snapshot com zero auditorias RNF17 antes e depois da projeção e nenhuma interação com o serviço de envio. | Vermelho e visita são critérios distintos. Com insumo local incompleto e pendência explícita ausente o comparador pode deixar a elegibilidade indeterminada. O contrato operacional de consulta, apresentação e encaminhamento segue separado. |
| Painéis | `PainelServiceImpl`, `PainelHistoricoServiceImpl`, `InicioServiceImpl` e `/api/painel/resumo` | Adaptador de teste alimentou o mapeamento atual de prioridade com faltas, risco, pendência e gatilho calculados localmente. | DTO da USF com cinco faltas e do CAPS AD com duas, sobre as mesmas projeções da agenda e busca ativa. | Distribuição, evolução mensal Envers, status ativo, filtros e resumo inicial ainda leem a identidade global. A revisão do corte deve preservar o propósito e a autorização de cada consulta. |
| PDF e Excel | `RelatorioServiceImpl`, `PdfRendererService`, `ExcelRelatorioService` e `/api/relatorios` | Adaptadores de teste montam DTOs existentes com os fatos locais e rótulos sintéticos. | Texto extraído dos dois modelos PDF. Leituras de células nos dois modelos XLSX confirmam faltas, presença/ausência, rótulos locais e exclusão de rótulos externos. Grupos agendados ou sem evidência ficam fora da exportação de frequência. | A busca ativa em relatório admite vermelho ou visita confirmada, com filtros de período, profissional e acompanhamento ainda vinculados às consultas legadas. A consolidação de participantes autorizados por grupo é uma dependência futura. |
| E-mail | `AlertaDestinatariosProviderPorUnidade`, `RelatorioEmailBuilder` e `AlertaPacientesVermelhosService` | O build de HTML recebe a linha sintética com contagem e data locais. | HTML contém somente o rótulo da USF vermelha, cinco faltas e a data prevista. Nenhum envio ou disparo de alerta foi usado para o ensaio. | A seleção de destinatários usa referências globais e recorta ATIVO/VERMELHO. A visita a vínculo amarelo entra na busca ativa e no relatório correspondente, com e-mail sujeito à regra própria. |

## Preservação, resultados e correções

| Comando ou execução | Resultado |
| --- | --- |
| Primeiro focado de leitura local, avaliador e diagnóstico consolidado | 15 aprovados, zero falhas, erros ou ignorados. |
| Primeira rodada dos testes novos | 24 aprovados e 1 erro. O adaptador de painel consultou campo inexistente no DTO. A correção passou a conferir os acessores tipados `classificacaoRisco` e `quantidadeFaltas`. |
| Maven focado com projeção, proveniência, comparadores, RF22, RNF17, isolamento e exportações | 213 aprovados na rodada ampliada e 21 aprovados no focado de projeção e autorização do SHA final. Zero falhas, erros ou ignorados nas duas rodadas. |
| `bash ./mvnw -B -ntp clean verify`, Java 21 | 1.297 aprovados em 112 suítes Surefire, zero falhas, erros ou ignorados. JAR empacotado. O Actions confirmou a mesma contagem. O pacote acrescentou 26 casos aos 1.271 da base. |
| `bash ./mvnw -B -ntp -Dtest=ReconciliationConcurrency61Characterization test` | 12 aprovados localmente e no Actions, zero falhas, erros ou ignorados. |
| Angular no workflow de cadastro | 28 aprovados no Actions (4, 5, 8 e 11) e build de produção aprovado. O bundle inicial de 858,72 kB manteve o aviso do orçamento de 500 kB. A execução local com Node 22 e npm 11.6.2 teve o mesmo resultado. |
| `git diff --check` | Aprovado. |

No focado de desenvolvimento, 190 de 191 casos passaram. A comparação do PDF falhou ao procurar um rótulo sintético longo como trecho contínuo do texto extraído. Rótulos curtos e distintos preservaram a verificação da presença local e da exclusão externa em PDF e Excel. Na rodada seguinte de um caso, o ensaio avançou até a concorrência e registrou um erro na delegação do Mockito ao método abstrato do repositório Spring Data. Um proxy exclusivo da fixture passou a coordenar a pausa e delegar à consulta original. A repetição do ensaio integrado passou com zero falhas, erros ou ignorados.

O teste integrado compara todas as tabelas, incluindo cadastro, vínculo, agenda, grupo, participação, histórico, V12/V13 e Envers. Uma decisão administrativa V13 e a solicitação V12 correspondentes já existiam no snapshot sintético e permaneceram iguais durante as leituras. Repetições obtiveram o mesmo resultado e o backup lógico H2 foi restaurado em outro arquivo e aberto em modo somente leitura, com quatro projeções idênticas. Uma transação de escrita confirmou a mudança de falta para presença na fixture enquanto um leitor estava entre a leitura de frequência e a consulta de histórico. O leitor preservou as frequências e o histórico anteriores. A próxima leitura viu a correção, a presença posterior à remarcação e o histórico novo juntos. As comparações antes e depois isolam as mudanças da própria transação de correção sintética e a leitura manteve o banco idêntico ao snapshot já corrigido.

## Alterações por arquivo

| Arquivo | Alteração |
| --- | --- |
| `ProjecaoClinicaVinculoService.java` | Composição, autorização, snapshot, prova de transição do status, indicadores, razões e comparação. |
| `AutorizacaoVinculoAssistencialService.java` e `VinculoAssistencialRepository.java` | Resolução diagnóstica do vínculo por paciente, identificador e unidade na própria consulta. |
| `LinhaDoTempoFrequenciasVinculoService.java` | Reusa os candidatos de frequência e fornece reservas e participações comprovadas à projeção no mesmo contexto. |
| `InventarioFrequenciasVinculoService.java` | Compartilha o inventário puro sobre a linha do tempo já lida. |
| `ComparadorPoliticaVisitaVinculo.java` | Reusa RF22 com a agenda comprovada e preserva o ponto de entrada já existente. |
| `ProjecaoClinicaVinculoIntegrationTests.java` | Quatro vínculos, acesso, estados locais, correção, transações, restauração e auditoria. |
| `ProjecaoContratosConsumidores.java` | Adaptadores sintéticos de painel, apresentação RF22 e renderização PDF, Excel e e-mail. |
| `ProjecaoRiscoPoliticaLegadaTests.java` | Comparações com a política real nos limites e em entradas equivalentes. |
| `AvaliacaoProvenienciaClinicaTests.java` | Caso explícito de ciclo entre elos da mesma unidade. |
| `etapa6-3-projecao-clinica-diagnostica-por-vinculo.md` | Evidências, divergências e dependências do corte. |

## Estado por frente

| Frente | Estado | Evidência ou dependência |
| --- | --- | --- |
| Composição interna, data de referência e escopo | **Concluído** | Projeção autorizada no perfil diagnóstico e resultado único consumido pelos adaptadores. |
| Frequência, remarcações, histórico, risco e RF22/RNF17 | **Testado** | Política equivalente onde os insumos são comprovados, contratos existentes e causas explícitas de inconclusão. |
| Painel, PDF, Excel e e-mail | **Testado** | Adaptadores de contrato e leitura dos formatos gerados a partir da mesma projeção. |
| Repetição, concorrência, snapshots e restauração H2 | **Testado** | Leituras iguais na cópia restaurada e versões coerentes sob escrita concorrente. |
| Conciliação do legado e evidência histórica de estado | **Pendente** | A equipe precisa decidir quem investiga, que provas são admissíveis e como encerrar ou reabrir casos inconclusivos. Uma deliberação administrativa V13 continua distinta da aplicação clínica. |
| Troca de consumidores, aplicação clínica e ensaio de implantação | **Pendente** | Exige revisão, autorização e ensaio com escopo e base aprovados. |

O aceite desta entrega é a revisão da projeção diagnóstica por vínculo. O fechamento da Etapa 6.3 depende das frentes posteriores.

[Consolidação dos diagnósticos](etapa6-3-consolidacao-diagnosticos-prontidao.md) · [Checkpoint do legado](etapa6-3-checkpoint-conciliacao-historicos-legados.md) · [Contrato do legado inconclusivo](etapa6-3-contrato-revisao-legado-inconclusivo.md) · [Gatilho de visita](etapa2a-gatilho-visita.md) · [Seleção de busca ativa](pre-etapa3-correcao-busca-ativa.md) · [Permissões, justificativas e auditoria](etapa3-permissoes-justificativas-auditoria.md)
