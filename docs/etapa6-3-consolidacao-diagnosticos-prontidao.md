# Etapa 6.3 — consolidação dos diagnósticos e ensaio de prontidão

## Estado inicial

A execução de 23/11/2026 encontrou a árvore limpa na branch local `work`, em `967d516`. Após buscar a branch `correcao-cadastro-unico-isolamento` no remoto, o checkout e o remoto apontavam para o mesmo commit-base, `0fcf51c270d5f6fc94273e6686d45e57ad5f8a20`, sem commits adicionais. A execução de referência teve os três jobs aprovados, com 1.257 testes Maven, 12 da matriz de concorrência, 28 Angular direcionados e build de produção. [GitHub Actions #45274781724](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45274781724)

## Inventário e correções

| Componente | O que já existia | Mudança e motivo |
| --- | --- | --- |
| V8–V13 | V8 cria o vínculo explícito. V9–V11 registram origem para novos fatos. V12/V13 preservam solicitação e decisão humana em tabelas separadas e auditadas. | O ensaio usa o modelo existente e as fixtures de migração offline. |
| `CadastroUnicoPreflight` e `OrigemClinicaPreflight` | Contagens de identidade, fonte e indícios V8. Agendamentos, históricos e sessões realizadas já exigiam revisão. | Grupos sem sessão e sessões agendadas ou canceladas podiam escapar do sinal de revisão. O relatório considera os dois conjuntos mesmo sem acompanhamento individual. Os preflights usam o mesmo critério para unidade atual configurada, sempre como indício. |
| `ClassificadorEvidenciaClinicaLocal` e `PreparacaoRevisaoProvenienciaService` | Triagem pura de metadados e rascunho local em memória com justificativa escrita. | O rascunho recusa fonte conhecida de outra unidade mesmo se a classificação priorizar evento sem origem ou integridade pendente. O classificador existente também é reutilizado no agregado. |
| `AvaliacaoProvenienciaClinica`, `Agendamento` e `AutorizacaoVinculoAssistencialService` | O autorizador local já percorria os ancestrais da remarcação. | A verificação compartilhada confere identidade, origem gravada, responsável, ciclo, estado canônico e elo obrigatório da remarcação. O diagnóstico e a autorização exigem o estado canônico explicitamente identificado. |
| `LinhaDoTempoFrequenciasVinculoService`, `InventarioFrequenciasVinculoService`, `DiagnosticoFaltasConsecutivasVinculoService` e `ComparadorPoliticaVisitaVinculo` | Linha do tempo, inventário e comparadores internos de risco, visita e busca ativa. A sequência filtrava pela origem do registro atual e pelo estado da sessão. | Um elo ancestral sem prova, uma frequência em sessão agendada ou cancelada, ou uma sessão realizada sem frequência, horário ou responsabilidade histórica comprovada podiam gerar um resultado aparentemente conclusivo. As ocorrências ficam fora da sequência e bloqueiam a conclusão. Inventário, linha do tempo e comparador de visita compartilham as verificações pertinentes. Quando presença e falta coincidem no mesmo instante, o desempate legado fica disponível somente como simulação, e a prontidão exige conferir a ordem. A política oficial permanece conforme as etapas anteriores. |
| `DiagnosticoProntidaoEstadoVinculoService` | Comparação local de faltas e indicadores básicos. | A prontidão distingue origem indeterminada, frequência inconsistente, estado local incompleto, indicadores locais pendentes e divergência de faltas ou última presença. Risco e gatilho de visita ausentes contam como estado incompleto. Uma última presença ausente é coerente quando a sequência comprovada e analisável também está sem presenças. |
| `VerificacaoFatoRevisaoProvenienciaService` e fluxo V12/V13 | Validação no servidor, escopo pelo vínculo, justificativa, fila e decisões auditadas. | Origem preenchida e participação, por si, podiam admitir na revisão local um histórico ligado a sessão ainda não realizada. A revisão recusa esse histórico, sessão realizada com responsabilidade inconclusiva e histórico sem a fonte exigida por seu tipo. Eventos manuais já previstos, com origem imutável e autor registrado, continuam elegíveis para conferência local. |
| `DiagnosticoConsolidadoMigracaoClinicaService` | Os diagnósticos de proveniência e de prontidão por vínculo funcionavam separadamente. | O agregado reúne cadastro, proveniência, frequências excluídas e estado clínico local em uma transação de leitura própria, com snapshot serializável no H2. |

## Como interpretar a prontidão

O agregado é um componente Spring interno disponível sob o perfil `diagnostico-migracao`. Para reproduzir o ensaio, a partir de `backend/buscaativa`, execute `bash ./mvnw -B -ntp -Dtest=DiagnosticoConsolidadoMigracaoClinicaIntegrationTests test`. Os testes ativam o perfil em bases sintéticas. O ensaio em cópia V13 restaurada usa `CorrectiveMigrationApplication` como executor de teste e abre o arquivo H2 com `ACCESS_MODE_DATA=r`. Use esse padrão de leitura para a cópia controlada. O executor de teste dispensa o runner operacional de recálculo e simula o adaptador SUS. O modo de abertura do H2 protege a cópia de escritas.

- Cada agendamento, grupo, sessão, participação e histórico entra uma única vez em uma das três categorias: **proveniência comprovada no modelo**, **origem sem comprovação** ou **inconsistência para conferência humana**. A origem gravada deve concordar com as relações de fonte, participação e vínculo aplicáveis. Em sessões realizadas, são aceitos os marcadores já existentes de confirmação da realização e de transição comprovada no Envers. A lotação atual serve apenas ao preflight de indícios.
- Históricos manuais de ação local podem ter proveniência íntegra sem fonte direta. Portar uma chave de origem em evento de falta sem fonte de frequência é uma inconsistência. Histórico manual antigo sem origem continua indeterminado. Os históricos preservam a trilha assistencial e a sequência usa os fatos de frequência de agenda e sessões. Coincidência de horário mantém ambos os fatos preservados.
- `frequenciasExcluidasDoEnsaio` conta registros de frequência candidatos uma única vez, incluindo os que ficaram sem vínculo ou prova e os incompatíveis com o estado da sessão. `prontidaoPorVinculo` conta vínculos por situação primária e pode sinalizar a mesma incerteza de identidade em mais de um vínculo, sem atribuir o fato à unidade.
- `REQUER_CONFERENCIA` indica que a base tem pendências técnicas. `COERENCIA_APENAS_PARA_TESTES` indica coerência nos critérios avaliados, inclusive quando a base está vazia. `migracaoAutorizada()` é sempre `false`. Documentos probatórios, estado clínico histórico e autorização de corte têm validação própria.

O resultado contém somente contagens e categorias. O perfil de ensaio dispensa rotas HTTP e o diagnóstico não participa do cálculo de indicadores ou dos fluxos de atendimento.

## Ensaios, dados e isolamento

| Verificação executada | Resultado |
| --- | --- |
| Maven direcionado aos diagnósticos, isolamento e revisão | **161 aprovados**, zero falhas, erros ou ignorados. |
| `mvn clean verify`, Java 21 | **1.264 aprovados** em 109 suítes Surefire, zero falhas, erros ou ignorados. JAR empacotado. Sete casos a mais que a base aprovada. |
| `ReconciliationConcurrency61Characterization`, matriz adicional prevista no workflow | **12 aprovados**, zero falhas, erros ou ignorados. |
| Angular direcionado do workflow | **28 aprovados** (4 permissões, 5 do contrato HTTP, 8 da conferência e 11 do cadastro legado). |
| `npm run build` | **Aprovado.** O bundle inicial de 858,72 kB excedeu em 358,72 kB o orçamento de aviso de 500 kB. Node 22.23.5 e npm 11.6.2 na execução local. |
| `git diff --check` | **Aprovado.** |

O teste que caracterizava o desempate legado preserva a asserção da contagem simulada e exige o bloqueio do resultado conclusivo. O ensaio integrado gravou uma identidade sintética com vínculos em duas USFs e em CAPS AD e II. Validou isolamento por ID nos dois sentidos, sequência própria por unidade, recusa de cadeia conflitante e de evidências clínicas incompletas no fluxo V12, e admissão de histórico manual comprovado para conferência local. O relatório encontrou 4 agendamentos comprovados, 2 sem origem comprovada e 2 inconsistentes, além de 6 frequências excluídas. A base de ensaio incluiu casos de sessão realizada com frequência confirmada, realizada com frequência ausente, com responsabilidade pendente, agendada com frequência indevida e cancelada sem frequência.

O teste comparou todas as tabelas e valores da base sintética após as leituras e após uma interrupção simulada. Antes da comparação verificou que `REVINFO` e os audits Envers de paciente, vínculo, agendamento, grupo, sessão, participação e histórico tinham entradas reais. O backup lógico H2 (`SCRIPT`) foi restaurado em outro arquivo, com colunas, linhas, IDs, relacionamentos, versões e revisões iguais. O preflight de cadastro repetido na cópia devolveu o mesmo resultado. Um segundo cenário restaurou o legado sintético já versionado até V13, abriu o arquivo em modo somente leitura e executou o diagnóstico consolidado. Os 12 agendamentos e os 6 históricos do legado continuaram sem origem comprovada, com os snapshots da base original e da cópia preservados. Os testes de migração executaram V8–V13 exclusivamente sobre fixtures H2 de arquivo e verificaram preservação das linhas e colunas originais, inicialização vazia de V12/V13 e restrições de integridade. A evidência do backup cobre a base sintética. A validação de volume e a restauração da base de implantação pertencem ao ensaio posterior em cópia autorizada.

As rotas locais existentes de agenda e histórico e o fluxo de cadastro protegido têm regressão HTTP. O ensaio JPA demonstrou isolamento da mesma identidade em unidades simultâneas, inclusive acesso interno por ID. Registros legados sem prova ficaram fora da leitura local e do recálculo de ensaio. As rotas operacionais legadas ainda usam, em parte, estado global e filtros de referência. A sua troca coordenada está na matriz abaixo.

## Revisão humana V12/V13

- A solicitação V12 exige profissional ou administrador no vínculo da unidade autenticada, fato local conferido no servidor e justificativa segundo a política vigente. A transação e a trava pessimista do vínculo serializam solicitações concorrentes. As consultas de paciente, unidade e ID e a fila administrativa impõem o escopo e paginam com até 100 resultados e desempate por UUID.
- A decisão V13 exige administrador local, trava o vínculo, reencontra o ticket no escopo e revalida o fato. A restrição única por pendência é uma segunda barreira para decisão duplicada. Os testes exercitaram concorrência, consultas diretas fora do escopo, paginação, contadores após decisão, as FKs compostas e as trilhas append-only. Solicitação e decisão são eventos separados. Aprovação é deliberação administrativa, com escrita clínica sujeita a operação futura e autorização própria.
- A triagem de legado desconhecido permanece agregada. A fila V12/V13 usa registros com proveniência local comprovada. O ID polimórfico do fato é conferido no serviço, e o banco amarra ticket e decisão a paciente e unidade por FKs compostas.

### Decisões funcionais em aberto

| Decisão | Alternativas para avaliação e implicação técnica |
| --- | --- |
| Quem investiga origem desconhecida | Equipe central com acesso por caso exigiria papel dedicado, concessão temporária, trilha de consulta e revisão de escopo. Uma triagem local com encaminhamento de evidências mínimas exigiria canal segregado e procedimento para fatos disputados entre unidades. |
| O que vale como evidência | Exigir um artefato contemporâneo autoritativo reduz ambiguidade e pode deixar mais casos inconclusivos. Permitir corroboração de fontes independentes exige regra de precedência, registro dos conflitos, retenção protegida e revisão do material. |
| Como tratar casos definitivamente inconclusivos | Encerrar a análise com justificativa e preservação da origem indefinida exige um estado terminal auditável. Prever reabertura diante de nova prova também exige histórico de deliberações e controle de concorrência. Em ambos, o fato permanece preservado e fora dos indicadores por unidade enquanto a prova faltar. |

## Matriz para o corte por vínculo

| Dimensão | Preparado e validável de forma independente | Dependência global ou humana | Mudança coordenada futura |
| --- | --- | --- | --- |
| Faltas consecutivas e última presença | Campos V8, cronologia local combinada e comparações de contagem e data de última presença. | `PacienteServiceImpl.recalcularAssiduidadePaciente` ainda grava em `Paciente`. Frequências pendentes, remarcações e legados indeterminados exigem conferência. | Transação por vínculo a partir da agenda e dos grupos. Substituir as leituras em `PacienteController`, `AgendamentoController`, histórico, início, painel, DTOs e exportações. Validar presença seguida de faltas em modalidades diferentes da mesma unidade. |
| Situação assistencial e acompanhamento | Campos locais e detecção de ausência. As fábricas preenchem `ATIVO` no vínculo novo. | Encerramento, reativação, validação de agenda e elegibilidade de grupo ainda consultam `Paciente`. O valor inicial do vínculo exige conciliação para representar o estado histórico. | Mover comandos e filtros de status/tipo de `PacienteServiceImpl`, `AgendamentoServiceImpl` e `GrupoTerapeuticoServiceImpl` para o vínculo autorizado. Ajustar `PacienteDTO`, listas, detalhe, `AgendamentoDTO` e DTOs de grupo. |
| Classificação de risco | Coluna local e `ComparadorPoliticaRiscoVinculo`, com pré-condições de sequência completa, tipo e pendências. | Classificação e alertas oficiais ainda usam `Paciente` e sua última presença. É necessária conciliação local dos insumos e revisão das transições. | Preservar a política vigente no recálculo transacional por vínculo. Reapontar `InicioServiceImpl`, `PainelServiceImpl`, `PainelHistoricoServiceImpl`, filtros e DTOs de risco. |
| Pendência e visita domiciliar | Campos V8 e comparador diagnóstico de cadeia inteira. Exercícios RF22 incluem última presença posterior e remarcações locais. | `PacienteServiceImpl`, listagem e painel usam pendência e gatilho globais. A incerteza histórica impede concluir a sequência ou transpor o gatilho. | Calcular RF22 sobre eventos e cadeia comprovados no vínculo. Preservar estados provisórios, textos, alertas e separação entre gatilho e risco. |
| Busca ativa e RNF17 | `ComparadorSelecaoBuscaAtivaVinculo` caracteriza elegibilidade e prioridade local. A trilha RNF17 existente distingue consulta, apresentação e encaminhamento. | A seleção em `PacienteServiceImpl` e a auditoria em `AuditoriaBuscaAtivaService` ainda partem do estado/escopo legado. | Reapontar critério, filtros, `AlertaBuscaAtivaDTO`, `AuditoriaBuscaAtivaController`, painel e exportações para a mesma autorização por vínculo. Preservar semântica e autoria dos eventos RNF17. |
| Histórico, listagens e relatórios | Leituras locais de agenda e histórico, proveniência de grupos e eventos e diagnósticos agregados. | `HistoricoPacienteServiceImpl` agrega pela identidade e lê indicadores de `Paciente`. `RelatorioServiceImpl`, `EscopoUnidadeService`, painéis e exportações ainda têm consumidores legados. Legados sem origem comprovada dependem da política de investigação. | Revisar rotas legadas, filtros, paginação, `HistoricoPacienteDTO`, `BuscaAtivaRelatorioDTO`, `FrequenciaGrupoRelatorioDTO`, `RelatorioController`, PDF, `ExcelRelatorioService` e renderização de e-mail. Verificar a mesma autorização na consulta e na exportação. |

## Classificação e plano para avançar

| Frente | Classificação |
| --- | --- |
| A — cobertura diagnóstica de proveniência e de frequência para a subfase | **CONCLUÍDA E VALIDADA** |
| B — diagnóstico consolidado interno de prontidão | **CONCLUÍDA E VALIDADA** |
| C — ensaio H2 sintético, isolamento, snapshots e restauração | **CONCLUÍDA E VALIDADA** |
| D — comparações sem escrita e matriz de dependências | **CONCLUÍDA E VALIDADA** |
| D — comandos, filtros, indicadores, DTOs e exportações no modelo por vínculo | **PENDENTE DE IMPLEMENTAÇÃO** |
| E — auditoria e guardas da revisão local V12/V13 | **CONCLUÍDA E VALIDADA** |
| E — investigação de fatos antigos de unidade desconhecida | **BLOQUEADA POR DECISÃO FUNCIONAL** |
| Ensaio em cópia autorizada da base de implantação e execução de corte | **PENDENTE DE IMPLEMENTAÇÃO** |

A cobertura desta subfase pode ser encerrada para as invariantes verificáveis pelo modelo atual. O agregado não avalia conteúdo probatório externo, volumes de implantação, correção histórica dos estados preenchidos por padrão nem a qualidade de dados fora das FKs do esquema. O pacote prepara o corte da Etapa 6.3, cuja conclusão depende das frentes pendentes e bloqueadas.

1. Implementar e testar a projeção clínica por vínculo em paralelo, com execução somente diagnóstica, a partir de frequências comprovadas. Cobrir consumidores de agenda, grupos, histórico, busca ativa, painéis e exportações como conjunto.
2. Obter aceite humano sobre permissão de investigação, suficiência das evidências e destino dos inconclusivos. Só então implementar a triagem protegida e a deliberação auditável do legado. Qualquer aplicação clínica exige um comando distinto e autorização específica.
3. Ensaiar o corte coordenado em cópia restaurável autorizada, com verificação de relações e Envers, rollback, concorrência e regressões RF22/RNF17. A ativação depende dos critérios de aceite clínico e técnico acordados.

Documentos de referência — [Diagnóstico inicial](cadastro-unico-isolamento-diagnostico-inicial.md), [Checkpoint do legado](etapa6-3-checkpoint-conciliacao-historicos-legados.md), [Contrato do legado inconclusivo](etapa6-3-contrato-revisao-legado-inconclusivo.md), [Etapa 3](etapa3-permissoes-justificativas-auditoria.md), [Etapa 4](etapa4-correcao-frequencia-retroativa.md), [Etapa 5](etapa5-regularizacao-cancelamentos-incorretos.md), [Etapa 6.2G](etapa6-2g-revisao-validacao-final.md).

## Verificação adicional da cadeia de remarcações — 23/11/2026

- Na revisão posterior ao commit `4577729d11ea25f49bb633aad3ca56f847783b4b`, foi identificada lacuna no avaliador compartilhado de proveniência: o fluxo de criação em `AgendamentoServiceImpl.validarOrigemDaRemarcacao` já exigia cronologia crescente, origem `SUBSTITUIDA` e ausência de sucessores paralelos, mas o diagnóstico por vínculo não reproduzia essas verificações.
- Ajustado o avaliador **somente de leitura** para exigir cada sucessor estritamente posterior ao antecessor, estado `SUBSTITUIDA` no antecessor, relacionamento de paciente/unidade coerente e ausência de outro sucessor (consulta de existência no repositório para fatos persistidos; verificação na lista fornecida no comparador puro). A criação e correção operacional de remarcações não foram modificadas.
- Testes puros cobrem cadeia legítima de dois sucessores, ordem anterior/igual, original não substituído, data ausente, sucessor sem raiz, conflito de unidade/paciente, sucessor paralelo e origem histórica ausente. Fixture do comparador de visita reproduz agora a transição `REMARCADO_ORIGEM` praticada pelo backend operacional.
- O relatório consolidado anterior e a classificação de prontidão para **testes** permanecem válidos apenas nos cenários efetivamente exercitados. **CI desta correção pendente**. Não houve alteração de migration, indicador clínico, prontuário, endpoint, permissão, tela ou corte do modelo por vínculo.
- Próximo pacote independente: projetar, comparar e validar indicadores clínicos por vínculo em modo diagnóstico, preservando as políticas RF22/RNF17 e sem conectar resultados às rotas operacionais. A conciliação dos legados sem origem continua bloqueada pela política humana de evidências e acesso.
