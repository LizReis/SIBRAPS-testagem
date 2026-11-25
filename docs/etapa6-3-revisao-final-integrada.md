# SIBRAPS — Revisão final integrada da Etapa 6.3

- **Data** — 25/11/2026
- **Parecer** — APROVADA COM RESSALVAS
- **Branch** — `correcao-cadastro-unico-isolamento`
- **Base da revisão** — `56040311ba3059ecdcde38c6b1114063c936336c`
- **Referência preservada de `main`** — `967d5162dd543ec9005608739128423903e75e30`

## 1. Resumo executivo

O checkout começou em uma branch local de trabalho na referência da `main`. A ponta remota da branch solicitada foi conferida e a revisão começou sobre a base esperada, com árvore limpa. O GitHub Actions confirmou o SHA integral e a aprovação dos três jobs do 6B.3C. O `verify`, a matriz opt-in, os contratos Angular direcionados e os dois builds passaram nessa CI. O workflow da branch executa testes Angular selecionados. A suíte completa foi medida localmente. [CI da base #45646638004](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45646638004)

O cadastro conciliado, as escritas assistenciais, o histórico operacional e os consumidores foram examinados como um conjunto. A revisão acrescentou verificações de persistência para a corrida documental entre unidades, verificou a serialização de duas certificações e fechou uma lacuna de inicialização da V18. A evidência abrange duas USFs, CAPS I, CAPS II e CAPS AD em testes complementares. A certificação mensal ainda carece de fonte independente aprovada e de um procedimento executável pelo administrador da unidade.

O material de requisitos acessível consistiu no pedido da revisão, nas decisões do repositório e nos contratos documentados. A matriz usa as regras funcionais e não funcionais ali registradas, incluindo RF21, RF22, RF26, RNF17 e as decisões sobre a Opção B. [Requisitos rastreados no diagnóstico](cadastro-unico-isolamento-diagnostico-inicial.md) · [Decisões de indicadores](etapa6-3-plano-indicadores-por-vinculo.md) · [Contrato do legado](etapa6-3-contrato-revisao-legado-inconclusivo.md) · [Transição 6B.3C](etapa6-3-pacote6b3c-transicao-operacional.md)

## 2. Matriz de rastreabilidade

Os nomes de classes abaixo identificam os testes existentes em `backend/buscaativa/src/test/java`. Os resultados por execução e a origem da evidência constam na seção 6.

| Regra | Implementação operacional examinada | Testes e evidências |
| --- | --- | --- |
| CPF/CNS globais e únicos, documentos normalizados e conflitos tratados | V17, `IdentidadeDocumentalPreflight`, `ConsultaCadastralExataService`, `CadastroAcompanhamentoLocalEnsaioService` e versão da identidade | `IdentidadeDocumentalMigrationTests`, `CadastroUnicoPreflightTests`, `ConsultaCadastroUnicoHttpTests` e `ModeloVinculoOperacionalTests`. Inserções simultâneas de profissionais de duas USFs em instâncias separadas resultam em uma identidade e somente o vínculo da vencedora. |
| Herança cadastral autorizada e vínculo clínico explícito | Conferência por documento e confirmação humana distintas. Escopo pelo ator e vínculo. Cadastro e edição por administração, profissional e recepção. `capsReferencia` é referência informativa | `ConfirmacaoCadastroUnicoHttpTests`, `CadastroUnicoIsolamentoReferenciaHttpTests`, `CadastroNovoVinculoLocalTests`, `EscritoresLocaisHttpTests` e `ModeloVinculoOperacionalTests`. Cobertura também para divergência de documentos, ausência de vínculo e versão cadastral em disputa. |
| Reserva, remarcação e cancelamento com escopo local | `ReservaIndividualLocalEnsaioService`, guardas de oferta e cadeia de remarcação. Frequência factual separada da situação da reserva | `IndividualReservation62Tests`, `ReschedulingIntegrity62Tests`, `CancellationConcurrencyTests` e `PersistenciaIndicadoresLocaisIntegrationTests`. Fato original, sucessores, regularização e rollback verificados com persistência. |
| Frequência individual e coletiva, correção retroativa, justificativa e autoria | `FrequenciaIndividualLocalEnsaioService`, `FrequenciaColetivaLocalEnsaioService`, `SerieGruposLocaisEnsaioService`, coordenação de escrita e trilha de correção | `SpecificFrequencyCorrectionTests`, `FrequencyCorrectionConcurrencyTests`, `CancelRecoveryIntegrationTests`, `GroupHistoricalResponsibilityTests`, `EscritoresLocaisHttpTests` e `PersistenciaIndicadoresLocaisIntegrationTests`. Conflito de versão, renumeração, transferência sem perda do responsável histórico e rollback da trilha. [Escritores 6B.1](etapa6-3-pacote6b1-integracao-escritores-backend.md) |
| Estado local, prontuário e histórico | `EstadoClinicoLocalEnsaioService`, `ConsultasClinicasLocaisEnsaioService`, `EvidenciaStatusVinculo` e origem comprovada por fato | `HistoricoClinicoLocalHttpTests`, `LeituraClinicaLocalUsfCapsTests`, `EstadoClinicoLocalEnsaioTests` e `PersistenciaIndicadoresLocaisIntegrationTests`. Histórico manual, transições, alta, inativação e reativação no vínculo. |
| RF22, situação de rua e RNF17 | `BuscaAtivaPainelLocalEnsaioService`, política local de risco/visita, `AuditoriaBuscaLocalEnsaioService` | `ComparadorPoliticaVisitaVinculoTests`, `HomeVisitRecalculationTests`, `RiskVisitRealFlowTests` e `PersistenciaIndicadoresLocaisIntegrationTests`. Vermelho elegível mesmo sem o gatilho de faltas, amarelo com gatilho confirmado e orientação de rua com ponto de referência ou sem ponto. Deduplicação e trilha da lista, visualização e encaminhamento por unidade. |
| RF21, painel, exportação e filtros | `BuscaAtivaPainelLocalEnsaioService`, `RelatoriosLocaisEnsaioService` e renderizadores PDF/Excel | `PainelHistoricoEscopoTests`, `EscritoresLocaisHttpTests` e `PersistenciaIndicadoresLocaisIntegrationTests`. Origem e responsável histórico, recusa de exportação integral inconclusiva, filtros locais e conteúdo exportado. |
| Legado inconclusivo, V12/V13 e Opção B | `RevisaoProvenienciaLocalEnsaioService`, `PainelPendenciasLocalEnsaioService`, registro de evidência, justificativa e decisão | `PendenciaProvenienciaConsultasEscopadasTests`, `DecisaoRevisaoPersistenciaAuditoriaConcorrenciaIntegrationTests` e `PersistenciaIndicadoresLocaisIntegrationTests`. Só entra na fila local o caso comprovável no escopo. A decisão registra deliberação e preserva fato, frequência e pendência clínica. [Revisão 6A](etapa6-3-revisao-complementar-pacote6a.md) |
| V16, V17, V18, marco, fechamento e filtros históricos | `AgendaMigrationTool`, validação de schema, `FechamentoHistoricoLocalService`, `HistoricoRiscoVinculoEnsaioService` e gráfico Angular | `ControleRf26MigrationTests`, `IdentidadeDocumentalMigrationTests`, `ModeloVinculoOperacionalTests`, `HistoricoRiscoVinculoIntegrationTests`, `painel.spec.ts`. V17 sem cobertura certificada, V18 com recusa de marco retroativo, limite mensal, conflito de manifesto, concorrência e restauração. |
| Monitoramento e RF26 | `MonitoramentoVinculoAgendado`, `MonitoramentoVinculosEnsaioService`, `ControleNotificacoesEnsaioService`, `RevisaoEntregaRf26Service` | `ModeloVinculoOperacionalTests`, `ControleRf26MigrationTests` e `PersistenciaIndicadoresLocaisIntegrationTests`. Agendamento por vínculo, recálculo em concorrência com escritores, revalidação e reserva durável entre instâncias, casos reservado/incerto, encerramento local e trilha pós-restauração. |
| Autenticação, autorização, trilhas e compatibilidade de entradas | `SecurityConfig`, `SecurityFilter`, `ModeloVinculoConfig`, controladores em `br.sibraps.modelovinculo` e autorização por ator/vínculo | `SecurityP01P02Tests`, `JwtKeyConfigurationTests`, `AutorizacaoVinculoPersistenciaTests`, `EscritoresLocaisHttpTests` e `ModeloVinculoOperacionalTests`. JWT, papéis, busca por vínculo, proteção experimental, H2 Console bloqueado e respostas documentais minimizadas. [Proteções anteriores](seguranca-p01-p02.md) |
| Angular padrão com contratos HTTP por vínculo | `raizClinica='/api'`, interceptador JWT, serviços de paciente, agendamento, grupo, início, painel e relatório | `cadastro-unico-service.spec.ts`, `contrato-clinico-integrado.spec.ts`, `cadastro-existente.spec.ts`, `agenda.spec.ts`, `detalhe-grupo-terapeutico.spec.ts`, `detalhe-paciente.spec.ts`, `painel.spec.ts` e `relatorios.spec.ts`. [Mapeamento das telas](etapa6-3-pacote6b2-integracao-consumidores-frontend.md) · [Rotas operacionais](etapa6-3-pacote6b3b-substituicao-modelo-legado.md) |

## 3. Evidências integradas

### Cadastro e isolamento assistencial

- Foram cruzadas as respostas HTTP do perfil padrão com as verificações de estado do banco. O teste novo usa dois profissionais com lotações diferentes e duas instâncias. Confere HTTP 201 e 409, uma única linha com CPF/CNS normalizados e exatamente um vínculo, na unidade que efetivou a criação. A conferência documental e a confirmação explícita são verificadas pelos testes de cadastro e do modelo operacional. O estado global legado fica separado do ciclo clínico local.
- Os testes operacionais de duas USFs cobrem login pelos três perfis, confirmação de vínculo, troca cadastral, agenda, correção individual, prontuário e grupo. Os testes complementares conferem persistência local de eventos, autoria, frequências, estados, indicadores e versões dos vínculos externos. CAPS I e CAPS II estão nos cenários do painel histórico. CAPS AD e CAPS II também estão nas correções, remarcações, grupos e transições. O conjunto valida padrões distintos de unidade. A combinação de todas as operações em uma única jornada HTTP com as cinco unidades permanece aberta.
- O escopo foi revisado defensivamente no JWT, serviços, DTOs, filtros do painel e dos relatórios, saídas PDF/Excel e guarda de rotas. A autorização depende da lotação do ator e do vínculo correspondente. A evidência dinâmica aproveita as asserções HTTP negativas existentes, inclusive resposta genérica a conflitos e limites de escopo. Resta uma revisão independente de segurança da configuração futura, incluindo logs, proxy reverso e acesso a backups. [Diagnóstico e preparação 6.3](etapa6-3-consolidacao-diagnosticos-prontidao.md) · [Validação anterior de escrita](etapa6-3-pacote6b1-integracao-escritores-backend.md)

### V18 e transição de indicadores

- V16 protege a unicidade da reserva RF26. V17 protege CPF/CNS normalizados de forma independente. V18 instala as estruturas prospectivas por unidade. O teste operacional confere instalação explícita e reexecução idempotente de V18. Em V17, o painel pode operar com meses sem cobertura e a operação de marco retorna 409 com indicação de V18. Uma instalação parcial ou contraditória é recusada antes do ORM.
- O marco usa relógio institucional, inventário inicial e administrador medidos na unidade. Um marco exatamente no início local do mês permite certificar esse mês após seu término. O teste rejeita o mês anterior ao marco e o mês corrente, e certifica o mês concluído no primeiro instante do seguinte. A reconstrução usa as revisões no corte do último milissegundo, com checagem de ordem das revisões e proveniência. A consulta filtra modalidade e situação de rua a partir do estado auditado do corte.
- Dois administradores da mesma unidade certificam a mesma competência em sessões concorrentes. Há uma única linha persistida. Uma resposta concorrente equivalente devolve o mesmo resultado e um conflito transitório admite repetição idempotente. Outro hash após a certificação resulta em 409. O painel lê o fechamento imutável, preservado após reinício e restauração. Correção posterior participa do recálculo local e da trilha própria. A reconstrução temporal foi testada com uma correção posterior ao corte. Falta observar a sequência inteira de certificar uma competência com população, corrigir frequência e reler o mesmo fechamento persistido via HTTP.
- O painel distingue zero comprovado, `SEM_COBERTURA` e `INDISPONIVEL`. O ponto atual vem em campo próprio. O gráfico interrompe séries mensais em lacunas. O teste do painel histórico com população varia o filtro de rua e o tipo de acompanhamento. O teste HTTP com V18 cobre zero comprovado e ausência de cobertura em outra unidade. [Decisões 6A.2](etapa6-3-pacote6a2-protecao-notificacoes-historico.md) · [Fechamentos 6B.3C](etapa6-3-pacote6b3c-transicao-operacional.md)

### Monitoramento e notificações

- O monitor padrão opera às 01:00 na zona de `app.alertas.zone` (`America/Bahia` por padrão). `sibraps.monitoramento.cron` configura o cron e `sibraps.monitoramento.habilitado=false` suspende o agendamento. A seleção paginada percorre vínculos ativos e revalida identidade, vínculo e situação sob trava. A transação por vínculo limita falhas isoladas. Instâncias podem percorrer o mesmo conjunto. O teste de concorrência mantém o vínculo correto depois da escrita clínica. A execução repetida propaga somente mudanças efetivas.
- RF26 reserva por dia, destinatário e unidade e revalida lotação e elegibilidade. O encerramento autorizado preserva a chave e registra estado anterior, ator, data e justificativa. `RESERVADA` e `INCERTA` pedem revisão humana e permanecem fora de reenvios automáticos. Os testes usam simuladores. O contexto operacional mantém o e-mail real desativado.
- A tentativa RF26 segura a reserva, as identidades ordenadas e o destinatário até a conclusão do simulador, mesmo com a transação suspensa durante a chamada. A espera de um escritor concorrente foi observada em teste. Uma unidade grande ou um simulador lento amplia a janela de contenção, inclusive quando unidades compartilham identidades. A paginação limita o lote do monitor, cujo trabalho pode se repetir entre instâncias. A revisão não encontrou evidência suficiente para estimar latência de cauda, esgotamento de pool ou frequência de deadlocks sob volume operacional. O horário deve ser avaliado com a operação, seguindo a configuração existente. [Coordenação de escritores e monitor](etapa6-3-pacote4b-coordenacao-escritores-monitoramento.md) · [Monitor e RF26 do 6B.3C](etapa6-3-pacote6b3c-transicao-operacional.md)

### Angular padrão

| Jornada | Consumidor e alcance verificado |
| --- | --- |
| Novo cadastro, conferência, confirmação e edição | Formulários de cadastro e conferência humana. O serviço lê os dados cadastrais permitidos, segue o contrato padrão e envia versão cadastral na edição. |
| Agenda, remarcação, frequências e grupos | `Agenda`, `NovoAgendamento`, páginas de grupos e detalhes. Contratos locais de status, reserva e frequência. Correção e regularização têm seus comandos e justificativas. |
| Prontuário, busca, painel e relatórios | Ficha e navegação para o histórico pelo identificador público, busca ativa no painel, auditoria RNF17, filtros, status de cobertura e exportações PDF/Excel. |
| Pendências | Aba administrativa de relatórios com fila, justificativas e decisão da Opção B. |
| Operações disponíveis pela API | Ativação do marco, certificação mensal, consulta e encerramento de ocorrências RF26. A tela do painel consome os fechamentos. Os comandos administrativos e a obtenção do inventário independente carecem de interface. O controle CSV de relatório também carece de exportação implementada. |

O build padrão usa `/api` e a configuração de ensaio substitui a raiz em tempo de build. As evidências combinam teste de contrato, inspeção dos consumidores e ensaio HTTP pelo proxy. O aceite visual e de usabilidade permanece humano. [Consumidores e telas do 6B.2](etapa6-3-pacote6b2-integracao-consumidores-frontend.md)

## 4. Defeitos e riscos encontrados

| Gravidade | Achado e como confirmar | Situação |
| --- | --- | --- |
| Alta | **Inicialização com V18 incompleta.** Em uma cópia sintética migrada até V18, mantenha o registro de sucesso e remova a coluna `indicadores` do fechamento. Na base revisada, a guarda da funcionalidade consultava apenas a presença das três tabelas e o histórico. A inicialização aceitava o schema e a falha aparecia no uso do recurso. O novo teste `ModeloVinculoOperacionalTests#inicializacaoRecusaV18RegistradaComEstruturaIncompleta` exige recusa durante a montagem. | Corrigido. |
| Média | **Teste Angular da ficha desatualizado desde o 6B.2.** A suíte completa falhava em dois casos de `detalhe-paciente.spec.ts` porque o teste simulava `buscarPorId` enquanto a tela usa `buscarDetalhe`. O teste também deixava a consulta do usuário logado alcançar o HTTP. A execução final passa nos dois casos e confere a navegação pelo identificador público. | Corrigido no teste da jornada. |
| Média | **Cinco casos e três rejeições assíncronas na suíte Angular completa.** `npm test -- --watch=false` reproduz as falhas restantes em `app`, `main-layout`, `detalhe-profissional`, `inicio` e `sidebar`. O primeiro ainda procura o título inicial em um seletor vazio. Os demais apontam `ActivatedRoute` ausente nos testes. As rejeições restantes são de chamadas a `/api/usuarios/me` sem simulação. | Pendências de infraestrutura de teste da família Q01–Q04. [Diagnóstico 6.2G](etapa6-2g-revisao-validacao-final.md) |
| Alta para a operação mensal | **Fluxo de certificação incompleto para o perfil do operador.** O contrato de fechamento exige IDs numéricos internos do inventário e o SHA-256. A ficha e a pesquisa usam UUID públicos. A inspeção dos serviços Angular mostra consulta do resultado pelo painel, sem fluxo de manifesto, conferência de fontes ou certificação. | Decisão de operação e interface pendente. Manter meses sem fechamento quando faltar evidência independente. |
| Média para a operação simulada em volume | **Espera de escrita durante a tentativa RF26.** O teste `tentativaRf26RetemTravasClinicasAteRetornoDoSimulador` confirma a espera enquanto o simulador está suspenso por uma barreira. A abrangência das travas cresce com o número de identidades da unidade. | Dimensionamento ainda aberto para ensaio de carga. |

## 5. Correções pontuais e regressões

- A inicialização operacional verifica o estado opcional da V18 antes do ORM. Um banco V17 íntegro continua elegível para inicialização. Uma V18 parcial, registrada com falha, sem registro coerente ou com colunas obrigatórias ausentes é recusada. A ferramenta offline verifica a mesma estrutura depois de `--transicao`. O teste confirma que a falha de inicialização preserva o schema sintético examinado. A checagem verifica registro, tabelas e presença das colunas utilizadas. Uma auditoria de tipos, constraints e manipulações posteriores do schema requer comparação do DDL e validação da restauração.
- A regressão operacional exercita a concorrência documental entre USFs, a mensagem explícita da funcionalidade indisponível em V17 e a unicidade do fechamento mensal numa corrida administrativa. As asserções anteriores foram preservadas.
- O teste Angular da ficha usa a resposta do contrato atual, controla a consulta do usuário logado e mantém a asserção de navegação. O código da tela é o mesmo da base.
- As migrations V16, V17 e V18 conservam os arquivos do commit de referência. Regras clínicas, permissões e requisitos de interface foram aplicados conforme os contratos anteriores.

## 6. Resultados e origem da evidência

### Executados nesta revisão

| Execução | Resultado observado |
| --- | --- |
| Java dirigido na base, cinco classes de cadastro, modelo operacional, persistência clínica, histórico e fronteiras USF/CAPS | 65 aprovados. |
| Java dirigido depois da correção, `ModeloVinculoOperacionalTests` | 4 aprovados, incluindo a nova recusa do schema incompleto, disputa documental entre USFs e certificação concorrente. |
| Angular completo na base | 123 aprovados, 7 falhas, 5 rejeições não tratadas, 32 arquivos. |
| Angular completo na entrega | 125 aprovados, 5 falhas, 3 rejeições não tratadas, 32 arquivos. A ficha passou nos dois casos. As demais falhas e erros continuam visíveis no resultado. |
| Contrato integrado Angular na configuração de ensaio | 4 aprovados. |
| Builds Angular de produção e ensaio | Aprovados. Produção com 896,21 kB iniciais frente ao aviso de 500 kB. Ensaio com 2,83 MB iniciais sem otimização. |
| Maven `clean verify` final, Java 21 | 1.393 aprovados, sem falhas, erros ou ignorados. JAR Spring Boot empacotado. |
| Matriz opt-in `ReconciliationConcurrency61Characterization` | 12 aprovados, sem falhas, erros ou ignorados. |
| Preparação offline a partir do JAR, Angular padrão, reinício e backup/restauração em H2 separado | Aprovado. V16 e V17 preparadas offline, V18 aplicada e reaplicada com zero migrations adicionais. Login JWT, prontuário e busca ativa via proxy do Angular. Após restauração independente, marco, prontuário, escopo de pesquisa e trilha RNF17 foram preservados. |

O ensaio do JAR usou o fixture legado do repositório em arquivo temporário. Com V17, o marco respondeu 409 e o painel 200 com períodos sem cobertura. Com V18, a criação respondeu 201 e o prontuário, o marco repetido e a busca ativa responderam 200 via proxy do Angular padrão. O cron de ensaio examinou um vínculo sem falha. Depois de `SCRIPT` e `RUNSCRIPT` em outro arquivo, a inicialização validou novamente as restrições documentais. A segunda USF retornou zero resultados para a identidade vinculada à primeira e zero eventos na sua consulta RNF17. A primeira recuperou o prontuário, o marco e o evento de auditoria. A sincronização externa de USFs e o envio real de e-mail estavam desativados no ensaio.

Maven 3.9.15 com JDK 21.0.13 e `TZ=UTC`. Angular 21 com Node 24.22.1 e npm 11.9.0 localmente. A CI executou com Node 22 e npm 11.6.2. O Flyway 11.14.1 emite aviso de que H2 2.4.240 excede a versão que o próprio Flyway declarou verificada, 2.3.232. O ensaio de migração usa a versão trazida pelo projeto.

### Evidências anteriores aproveitadas

| Fonte | Alcance reutilizado |
| --- | --- |
| CI do SHA em revisão e relatório 6B.3C | CI da base aprovada. O relatório registrou 1.392 testes Maven, 12 opt-in, 59 Angular direcionados no padrão, 4 no ensaio e dois builds. Registra também JAR com login, migrações de V16 a V18 e cron sobre dados sintéticos. [CI exata da base](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45646638004) · [Relatório 6B.3C](etapa6-3-pacote6b3c-transicao-operacional.md) |
| Consolidações, 6A/6A.2, 6B.1 e 6B.2 | Contratos de escrita e leitura, escopo de prova e reconciliação, rollback, conteúdo PDF/Excel, trilha RNF17 e respostas HTTP no ensaio. [Consolidação de prontidão](etapa6-3-consolidacao-diagnosticos-prontidao.md) · [Pacote 6A.2](etapa6-3-pacote6a2-protecao-notificacoes-historico.md) · [Escritores 6B.1](etapa6-3-pacote6b1-integracao-escritores-backend.md) · [Consumidores 6B.2](etapa6-3-pacote6b2-integracao-consumidores-frontend.md) |
| 6B.3A e 6B.3B | Preparação da unicidade documental, substituição conjunta de rotas, isolamento do artefato de ensaio e inicialização V17. [Unicidade V17](etapa6-3-pacote6b3a-unicidade-documental.md) · [Substituição do modelo](etapa6-3-pacote6b3b-substituicao-modelo-legado.md) |

## 7. Limitações técnicas e operacionais

- A cobertura combina cenários operacionais HTTP, transações persistidas e relatórios anteriores. Permanece aberta uma jornada ponta a ponta no navegador com as cinco unidades e os três papéis, bem como a disputa entre correção factual e certificação V18 de uma mesma competência contendo pacientes.
- Falta evidência de carga para certificação mensal com população representativa. O serviço consulta a trilha histórica por vínculo e recalcula combinações de modalidade e situação de rua mantendo uma transação serializável. O volume mensal, a duração das travas e o tempo de leitura do painel com relatórios concorrentes precisam ser medidos.
- A análise de acesso foi defensiva, por código, contratos e testes existentes. A cobertura de entradas HTTP corresponde às rotas e cenários registrados, com revisão independente da configuração de implantação ainda pendente.
- A validação é sintética e restrita a H2. Ficam fora da evidência banco institucional, rede de implantação, integração SMTP, dimensionamento de múltiplas instâncias sob carga e validação de qualquer SGBD diferente.

## 8. Aceitação humana

- Administração, profissional e recepção precisam validar cadastro, suspeita de duplicidade, confirmação de vínculo, perda de versão e mensagens de indisponibilidade. Incluir identificação e referência territorial sem sugestão de compartilhamento clínico.
- Profissionais precisam conferir clareza de presença, falta, cadeia de remarcação, cancelamento regularizado, correção com justificativa e efeito na busca ativa. Incluir o roteiro de situação de rua com e sem ponto de referência.
- A administração da unidade precisa conferir a fila da Opção B, os rótulos de cobertura do painel e a interpretação dos casos RF26 `RESERVADA` e `INCERTA`. O uso de fechamento mensal precisa ser ensaiado pelo papel responsável com população e fontes sintéticas representativas.

## 9. Decisões de negócio pendentes

### Inventário independente e SHA-256

O contrato atribui a produção da evidência à operação autorizada e a certificação ao administrador da unidade. Falta designar formalmente o responsável por produzir e conferir o inventário. O papel produtor precisa acessar uma fonte independente no escopo devido. O conteúdo deve identificar cobertura, população, exclusões, período e zona, fontes cadastrais e assistenciais, diferenças e lacunas. O sistema atual compara o manifesto com as revisões disponíveis no corte. O hash declarado identifica bytes de referência para futura conferência. Autenticidade, completude e custódia exigem evidência própria.

Os UUIDs visíveis ao administrador na ficha são distintos dos IDs internos numéricos aceitos no manifesto. Hoje a obtenção desses números depende de uma extração autorizada ou de suporte técnico com acesso ao banco. Também faltam uma interface de envio/conferência e um procedimento para guardar o arquivo original, vincular seu hash à versão e torná-lo recuperável para auditoria. Exigir do administrador consulta SQL, geração manual de hash e montagem do JSON torna o processo dependente de conhecimento técnico externo ao trabalho da unidade.

As alternativas para deliberação são manter competência sem cobertura até existir evidência suficiente, aprovar um procedimento assistido com extração controlada e conferência do inventário independente, ou aprovar uma interface que importe o inventário externo, mapeie identificadores da unidade e apresente divergências antes da certificação. Definir também a custódia, o acesso e a retenção do arquivo. Um inventário extraído apenas da mesma trilha que o sistema compara não constitui uma segunda fonte. [Contrato V18](etapa6-3-pacote6b3c-transicao-operacional.md) · [Decisão histórica 6A.2](etapa6-3-pacote6a2-protecao-notificacoes-historico.md)

### Legado sem origem, retificação de mês e RF26

A investigação especial do legado sem prova segue adiada. Duplicidades e documentos inconclusivos em banco pré-V17 exigem análise cadastral explícita. Cabe decidir eventual publicação separada de uma retificação de competência já certificada, compatível com a preservação do fechamento original. Para RF26, decidir governança das reservas incertas, equipe de revisão e eventual transporte externo em etapa própria. Nenhuma dessas decisões é condição para o cálculo local já aprovado. [Legado inconclusivo](etapa6-3-contrato-revisao-legado-inconclusivo.md) · [Política de indicadores por vínculo](etapa6-3-plano-indicadores-por-vinculo.md)

## 10. Condições para futura implantação

1. Autorizar ambiente, dados e operadores. Ensaiar restauração independente de uma cópia autorizada, proteger credenciais e backups e confirmar integridade das trilhas.
2. Validar histórico Flyway, constraints, diagnóstico documental e contagens de controle por unidade. Resolver casos bloqueantes pelo fluxo autorizado. Programar janela offline, checklist de reversão e critério de interrupção antes de qualquer migração.
3. Validar navegadores e jornada real com os três papéis em ambiente representativo. Avaliar a suíte Angular completa como critério de liberação, além da CI direcionada. Conferir configuração da API, isolamento pós-restauração e rastreabilidade de relatórios e auditorias.
4. Medir tempo de execução, espera de escritores, tamanho do pool, comportamento de mais de uma instância e compatibilidade do horário do monitor com a operação. Manter RF26 em simulação até deliberação e validação próprias.
5. Se a certificação mensal fizer parte do primeiro corte, aprovar responsabilidades, fontes, identificação e procedimento de custódia. Na ausência de evidência independente, manter a competência sem cobertura.

## 11. Parecer de encerramento

**APROVADA COM RESSALVAS para encerramento técnico.** A identidade única, a separação clínica por vínculo e os fluxos operacionais têm evidência de integração e persistência em H2 sintético. A revisão confirmou a corrida documental entre unidades, a concorrência de fechamento e a restauração, e tornou a inicialização da V18 segura diante da falha de estrutura reproduzida. A suíte diagnóstica Angular ainda apresenta falhas conhecidas e a certificação mensal precisa de aceitação do procedimento de evidência.

**Aceitação pelos usuários:** pendente para as jornadas da seção 8 e para a viabilidade da certificação pelo administrador.

**Implantação real:** requer decisão própria após as condições da seção 10, com validação do ambiente e dos dados autorizados.
