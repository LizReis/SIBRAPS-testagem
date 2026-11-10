# Etapa 3 — permissões, justificativas e auditoria

## B1 — diagnóstico anterior à implementação

Base recebida: `6ad7e0c18b59e8292e7748a358cf92ec3fe7ed38`, branch `correcao-fluxos-assistenciais-sibraps`, checkout limpo. Baseline reproduzido: 382/382. Entrega A concluída primeiro no commit `e514a85`, verify 401/401, conforme [relatório próprio](pre-etapa3-correcao-busca-ativa.md). Nesta seção registram-se decisões antes de implementar a Etapa 3; resultados serão acrescentados após execução.

### Regras confirmadas e matriz de responsabilidade

A responsabilidade individual é `Agendamento.usuario`, nunca o profissional de referência do paciente nem o perfil genérico. Em grupos, o modelo existente só associa o responsável por `SessaoGrupo.grupo.coordenador`; essa relação será usada, sem inventar um agendamento individual. Reatribuição de coordenação por colega não poderá servir de atalho para se autorizar.

| Perfil | Registro inicial individual | Correção própria | Correção de colega da unidade | Outra unidade | Grupo |
|---|---|---|---|---|---|
| PROFISSIONAL | Atendimento atribuído ao autenticado | Permitida com motivo | 403 | 404 | Coordenador do grupo, inclusive registro/confirmacão e correção da sessão |
| ADMINISTRADOR | Própria unidade | Permitida com motivo | Permitida com motivo | 404, incluindo outra USF específica/CAPS | Supervisiona somente a própria unidade e seus participantes |
| RECEPCAO | Operação inicial individual já existente preservada | 403 | 403 | 404 | Sem frequência clínica/correção; administração legítima existente preservada |
| Não autenticado | 401 na API | 401 | 401 | 401 | 401 |

Escopo e autorização precedem sucesso idempotente. Sem mudança efetiva não haverá nova correção nem justificativa inventada; um motivo eventualmente enviado será ignorado como correção inexistente. Versão obsoleta continua conflito. Não se antecipa regularização de cancelamentos.

### Caminhos inspecionados e proteção prevista

| Caminho | Diagnóstico / decisão |
|---|---|
| PATCH /api/agendamentos/{id}/status | Perfil genérico admitia colega; idempotência vinha antes de verificar perfil. Aplicar responsabilidade no serviço, manter locks paciente → profissional → atendimento e acrescentar motivo opcional no contrato, obrigatório para mudança de frequência registrada. |
| POST /api/agendamentos | Serviço já ignora frequência enviada e cria somente reserva; manter e testar que campos canônicos/DTO não contornam a proteção. |
| PATCH /api/grupos/sessoes/{id}/frequencias | Corrigia lote sem motivo, sem responsável e sem distinguir itens inalterados. Proteger sessão inteira, auditar cada mudança e reverter todo lote se qualquer etapa falhar. |
| PATCH /api/grupos/sessoes/{id}/participantes/{paciente}/presenca | Só admite provisório em sessão AGENDADA; proteger pelo coordenador, não permitir corrigir fato de sessão REALIZADA por essa rota. |
| POST /api/grupos/sessoes/{id}/confirmacao-ocorrencia | Primeiro fechamento clínico, sem motivo artificial. Coordenador/admin, escopo de todos os participantes e controle concorrente. Sessão fechada não reabre por esse caminho. |
| POST /api/grupos/{id}/inscricoes-retroativas | Registro inicial histórico, não substituição de frequência existente (já recusada). Exigir coordenador/admin; conservar auditoria de inscrição existente. |
| PUT /api/grupos/{id} | Profissional não poderá assumir grupo de colega para depois corrigir suas sessões. Administração existente por admin/recepção permanece no escopo. |
| Demais inscrições/remoções futuras e status de sessão | Preservar ausência de alteração de fatos já realizados e barreiras existentes; retorno de caminho clínico também verifica responsabilidade. |
| Histórico manual | Eventos narrativos não alteram a fonte canônica de frequência; não substituir por essa rota uma correção auditada. |
| GET /api/pacientes/busca-ativa | Deve registrar CONSULTA_LISTA, sem fingir visualização individual. |
| Novos eventos de alerta | Backend distinguirá visualização explicitamente declarada pela interface e registro formal explícito de encaminhamento; nenhuma consulta automática produzirá encaminhamento ou envio externo fictício. |

### Contratos e auditoria a implementar

A API legada será o único adaptador de correção individual: `novoStatus`, `version` e `justificativa`. Não haverá rota alternativa sem a mesma política. Grupos receberão justificativa no DTO de correção em lote. Campos de autoria/perfil/unidade/instante serão exclusivamente obtidos no servidor. O Angular atual não terá permissão de corrigir sem motivo só para preservar sua interface antiga; adaptação visual completa fica na Etapa 8.

Pretende-se persistir uma auditoria append-only por correção efetiva, com alvo individual OU sessão/participante, paciente, ator real, perfil/unidade específicos, estado anterior/posterior, reserva/sessão no instante, justificativa, Instant UTC e versões. Histórico assistencial e Envers serão preservados como mecanismos distintos. Registros antigos não receberão justificativa ou autoria reconstruída. V1/V2 permanecerão imutáveis; tabelas, índices, FKs e proteções serão uma nova migração aditiva.

A validação de motivo será compartilhada entre Bean Validation e serviço, com normalização documentada, limite compatível com o banco e recusa de textos genéricos. A consulta de auditorias usará DTOs, paginação limitada e escopo/responsabilidade; recepção não receberá justificativas clínicas. Falha de histórico ou auditoria deve reverter frequência, indicadores e todas as gravações do lote. Testes devem usar HTTP/JPA, transações independentes e arquivo H2 legado/migrado, incluindo reabertura e restauração.

## B2 — responsabilidade implementada

`AutorizacaoClinicaService` centraliza a política nos serviços. O PATCH individual verifica responsabilidade antes de tempo/versão/idempotência; colega da mesma unidade recebe 403 e recurso externo 404. O registro inicial individual pela recepção permanece permitido como antes, mas nenhuma correção clínica é concedida a ela. O coordenador existente governa a clínica de grupo; correção valida todos os participantes, incluindo os não alterados. Profissional não pode assumir grupo de colega por PUT para criar sua própria autorização. Confirmação e inscrição retroativa inicial também verificam responsabilidade; inscrição retroativa não pode sobrescrever frequência existente.

`ClinicalAuthorizationTests`: **23/23 aprovados**, zero falhas/erros/ignorados, 11,904 s, término **18:16:49 de 10/11/2026**. Testes HTTP e chamada direta ao serviço cobrem responsável, colega, profissional de referência sem atribuição, admin, recepção, unidade externa, USFs específicas distintas, não autenticado, original substituído, idempotência, lote com participante externo, presença provisória e tentativa de autoatribuição do grupo. A primeira execução teve duas falhas porque a fixture de sessão realizada não possuía seu histórico clínico; a fixture foi completada sem modificar a regra de produção. O baseline completo será reexecutado após adaptar os contratos de justificativa na B3 e conservar seus cenários com as novas expectativas autorizadas.

## B3 — contrato único de justificativa

Individual: `PATCH /api/agendamentos/{id}/status?novoStatus=PRESENTE&version=7&justificativa=...`. `version` mantém sua compatibilidade opcional; motivo passa a ser obrigatório para mudança efetiva de frequência conhecida, inclusive original SUBSTITUIDA. A assinatura antiga do serviço encaminha motivo nulo para a mesma validação e não é um bypass. Não foi criado segundo endpoint de correção (JU-09 não aplicável). Campos canônicos somente de resposta continuam incapazes de escrever frequência por cadastro.

Grupo: `PATCH /api/grupos/sessoes/{id}/frequencias`, corpo `{ "frequencias": [{ "pacienteId": "UUID", "statusPresenca": "PRESENTE" }], "version": 0, "justificativa": "Descrição explicativa do erro anterior." }`. O lote é validado antes de alterar participantes; somente diferenças efetivas seguem para histórico/recálculo. Frequência final ausente em sessão realizada fica em conflito de revisão, sem reconstrução arbitrária. Itens inalterados não criam correções. Registro provisório de sessão AGENDADA não equivale a fato final; o primeiro fechamento permanece sem motivo artificial e exige coordenador/admin.

`PoliticaJustificativaCorrecao` aplica Bean Validation com `JustificativaCorrecaoDTO` também no serviço: obrigatório, 10–2000 caracteres, rejeição do texto bruto acima de 2000, NFC e normalização controlada de espaços. Recusa controles não textuais e os padrões genéricos Correção, Alteração, Sem motivo e Não informado, incluindo variações de acento/caixa/ponto final. O mínimo é uma restrição técnica de qualidade de entrada, não regra clínica nem prova automática da veracidade do texto. Mensagens não reproduzem o conteúdo enviado. Motivo sem mudança efetiva é ignorado após autorização/escopo/versão, sem novo evento clínico.

Focado autorização/contrato: **43/43**, zero falhas/erros/ignorados. Verify completo: **444/444**, BUILD SUCCESS em **45,320 s**, término **18:25:10 de 10/11/2026**. A primeira regressão completa mostrou a caracterização antiga `sameUnitColleagueRetainsCurrentPermissionUntilStageThree`, que expressamente aguardava esta etapa: preservou-se o cenário como `sameUnitColleagueCannotCorrectAfterStageThree`, agora esperando 403 e snapshot integral inalterado. Três testes de recepção passaram de 422 para 403, conforme convenção aprovada; permanecem recusados. Helpers de correções anteriores passaram a fornecer motivo sintético explícito; nada foi preenchido automaticamente em produção. Os testes de registro inicial, colisão, oferta histórica, remarcação, rollback, idempotência e migração não foram removidos. Total: 382 anteriores + 19 busca ativa + 23 responsabilidade + 20 justificativas.

O Angular atual não envia justificativa nas correções existentes. Essa incompatibilidade temporária é intencionalmente protegida pelo backend: correções sem motivo recebem 422. A Etapa 8 deverá enviar o parâmetro individual e o campo do lote acima, mostrar validação e consultar auditorias; não foi criado motivo padrão nem relaxamento de segurança para a UI antiga.

## B4 — auditoria clínica persistente e migração V3

### Modelo, autoria e consulta

`AuditoriaCorrecaoFrequencia` armazena UUID próprio, agendamento OU sessão/participante, paciente, ator autenticado, perfil e chave específica da unidade no instante, responsável pelo atendimento, tipo, reserva/sessão, frequência anterior/nova, justificativa normalizada, `Instant` do `Clock` do servidor e versões anterior/resultante. Não recebe esses metadados de autoria pelo cliente. A correção feita pelo administrador identifica esse administrador como executor e conserva o profissional atribuído como responsável assistencial.

A entidade é `@Immutable`, sem setters; o repositório expõe somente consulta/paginação/count. Inserção usa `EntityManager.persist` em transação **MANDATORY**, após o histórico/recálculo e flush das alterações. Cada correção é uma nova linha. Não é presença/falta nova e não substitui `HistoricoPaciente` nem Envers. Nos grupos, cada participante efetivamente alterado gera uma linha sem inventar agendamento individual. Itens inalterados não geram histórico corrigido nem auditoria.

Leitura por DTO específico, ordenação decrescente por instante/versão/UUID e paginação de 1–100 itens:

- `GET /api/agendamentos/{id}/correcoes?page=0&size=20`.
- `GET /api/grupos/sessoes/{id}/correcoes?page=0&size=20`.

Administrador somente da unidade; profissional somente responsável/coordenador; recepção recebe 403 e unidade externa 404. A consulta também compara a unidade histórica do registro com o escopo atual. Não há PUT/DELETE dessas auditorias. A API retorna 405 para tentativa de mutação pelos caminhos de leitura.

### Atomicidade e concorrência

Individual conserva **paciente → profissional → agendamento**. Captura versão/frequência anteriores sob lock e grava a versão realmente persistida, não uma estimativa. Grupos passam a bloquear pacientes em ordem estável de UUID, depois grupo e sessão. Escritores de participantes e reatribuição do grupo compartilham a proteção; uma lista alterada enquanto os locks eram obtidos é recusada com conflito seguro. Correção efetiva força uma única evolução da versão da sessão, antes da auditoria; tentativa idempotente não incrementa versão. Lote inteiro é atômico.

Falhas de histórico ou de armazenamento da auditoria revertem dados clínicos, indicadores, versão, Envers e auditorias já inseridas na mesma transação. Não há transação REQUIRES_NEW para confirmar uma auditoria independentemente da correção. Os testes injetam falha **após INSERT/flush real** e no segundo participante do lote, comparando todas as tabelas do banco antes/depois. A instrumentação do teste usa o alvo do proxy; a proteção MANDATORY não foi enfraquecida para facilitar o teste.

### Proteção no banco e compatibilidade

`V3__auditoria_correcao_frequencia.sql` acrescenta tabela, FKs, índices por atendimento/sessão/instante e checks: alvo individual/grupo coerente, frequências distintas P/F, motivo dentro do tamanho, versão resultante superior e perfil clínico permitido. Não altera V1/V2, dados anteriores ou revisões. Nenhuma justificativa antiga é fabricada.

O trigger H2 `AuditAppendOnlyTrigger` recusa UPDATE/DELETE, inclusive via DML direto. `AuditoriaSchemaGuard` verifica a proteção antes de servir a aplicação; trigger ausente bloqueia e não é reparado automaticamente em arquivo persistente. Instalação automática de triggers é restrita a H2 **em memória**, com `ddl-auto=create/create-drop` explicitamente configurado para a base descartável. Persistentes continuam exigindo migração explícita. A verificação libera sua conexão antes de adquirir outra, e foi testada com pool de uma conexão.

A dependência H2, já existente e na mesma versão, passa de runtime para compile somente para implementar sua interface de trigger. Não foi adicionado outro framework de teste. A implementação é um adaptador H2; outro SGBD exige sua própria migração/proteção validada. Essas medidas protegem as operações normais e DML, **não tornam o registro inviolável contra um administrador de banco capaz de alterar DDL ou substituir o artefato**. Separação de credenciais/privilégios, guarda externa e política operacional precisam ser avaliadas na implantação.

### Evidências da B4

- `ClinicalAuditIntegrationTests`: 22 execuções, incluindo duas direções, autoria/tempo forjados recusados, supervisor real, ordem/paginação, imutabilidade, leitura negada, registro inicial, substituído, idempotência, rollback e quatro disputas reais (responsável/admin, grupo com ordem inversa, cancelamento e remarcação).
- `AgendaLegacyMigrationTests`: nove cenários preservados; somente o número esperado de migrações iniciais foi de 2 para 3. Os checks de IDs, colunas antigas, vínculos, versões, ocupação, ambiguidades, histórico, Envers e restauração permanecem.
- `ClinicalAuditMigrationTests`: seis execuções, partindo do schema antigo e de V1/V2 já aplicadas, checks/FKs, proteção ausente, base dev descartável e ciclo real de aplicação/backup. A correção feita por PATCH no arquivo migrado permanece consultável em **três inicializações** (original, reaberta e restaurada). O backup mais recente conserva também a auditoria; reaplicação executa zero migrações. A migração em si cria zero auditorias históricas.

Focado clínica + regressões de migração: **31/31**. Focado persistente final: **6/6**, em 22,445 s, término **18:44:07 de 10/11/2026**. Verify completo da B4: **472/472**, zero falhas/erros/ignorados, BUILD SUCCESS em 49,830 s, término **18:43:01**; depois dele, o ajuste de liberação de conexão do guard foi exercitado novamente no focado com pool mínimo e reinicialização. A suíte integral será repetida na B6 com a composição final.

Na preparação dos testes persistentes, foram corrigidos problemas das fixtures: dependência de teste de segurança não presente (substituída pela cadeia real já disponível), dois mocks SUS primários e ciclo de vida da memória sem pool. Não houve relaxamento de validação/segurança de produção para fazê-los passar. Nenhum banco real ou persistente compartilhado foi usado.

## B5 — RNF17: suporte backend, sem afirmar integração visual concluída

### Eventos e contratos

`AuditoriaBuscaAtiva` é uma trilha operacional separada da auditoria clínica, de `HistoricoPaciente` e do Envers. Persiste UUID, paciente quando aplicável, ator real, perfil/unidade no momento, instante UTC, tipo, identificador de requisição, situação consultada/registrada, risco/indicadores/gatilho confirmados no instante, situação de rua, quantidade e, somente no encaminhamento, destino/descrição operacionais. Não copia endereço, referência, CPF ou CNS para a auditoria operacional.

| Operação | Contrato | Evento efetivamente registrado |
|---|---|---|
| Consulta da lista existente | GET /api/pacientes/busca-ativa | CONSULTA_LISTA_BUSCA_ATIVA, quantidade e critérios da unidade; paciente nulo. Não significa que cada alerta foi visto. |
| Confirmação explícita de apresentação individual | POST /api/pacientes/{uuid}/alertas/visualizacoes, `{ "idRequisicao": "UUID" }` | VISUALIZACAO_ALERTA somente após chamada explícita autorizada. A Etapa 8 deve chamá-la quando realmente abrir/apresentar o alerta. |
| Registro formal explícito de encaminhamento | POST /api/pacientes/{uuid}/alertas/encaminhamentos, `{ "idRequisicao": "UUID", "destino": "Equipe ...", "descricao": "Descrição da ação formalmente registrada." }` | ENCAMINHAMENTO_ALERTA. Registra a declaração operacional autorizada; não envia mensagem, não prova recebimento por outro serviço e não ocorre por abrir/consultar alerta. |
| Histórico operacional do paciente | GET /api/pacientes/{uuid}/alertas/auditoria?page=0&size=20 | Consulta protegida; não cria visualização nem encaminhamento. |
| Consulta administrativa da unidade | GET /api/alertas/auditoria?page=0&size=20 | Somente administrador, no escopo autenticado. |

Criação retorna 201; repetição idêntica pelo mesmo ator/chave retorna 200 com o mesmo recibo, sem evento novo. Reutilização da chave para outro paciente, tipo, unidade ou dados operacionais retorna 409 sem expor o evento anterior. A chave não é prova de identidade; autoria vem sempre do contexto autenticado. O recibo de uma ação já registrada não representa uma nova visualização se o alerta tiver mudado depois. A precisão dos instantes é normalizada a microssegundos, compatível com TIMESTAMP(6), garantindo resposta idempotente também após releitura do banco.

### Permissões e situações

| Perfil | Consultar lista / registrar visualização | Registrar encaminhamento formal | Consultar trilha operacional |
|---|---|---|---|
| ADMINISTRADOR | Própria unidade | Própria unidade, situação confirmada | Paciente e lista administrativa da unidade |
| PROFISSIONAL | Própria unidade | Própria unidade, situação confirmada | Paciente da unidade; sem consulta administrativa global |
| RECEPCAO | Acesso operacional preexistente da própria unidade | 403 | 403; não recebe descrições operacionais adicionais |
| Não autenticado / outra unidade | 401 / 404 no recurso | 401 / 404 no recurso | 401 / 404 no recurso |

A situação de alerta é compartilhada pela equipe da unidade; isso não concede autorização para corrigir atendimentos de colegas. A auditoria clínica continua restrita ao responsável/coordenador ou administrador. USF específica é considerada. Consultas administrativas também conferem o escopo **atual** do paciente, além da unidade histórica, para não expor pacientes transferidos. Registros são preservados mesmo quando deixam de ser acessíveis no escopo anterior.

Visualização pode registrar `REVISAO_HISTORICO` para indicadores provisórios, com `gatilhoConfirmado=false`; encaminhamento como alerta confirmado é recusado nesses casos. Paciente inativo ou situação resolvida não recebe evento novo. Risco vermelho sem gatilho e risco amarelo com gatilho confirmado são situações diferentes e válidas. A situação de rua distingue busca em referência de visita, sem declarar que uma visita foi realizada. Os endpoints usam os indicadores persistidos sob lock; uma tela obsoleta deve atualizar a lista quando a situação deixou de ser elegível.

Destino tem limite de 200 caracteres e descrição operacional de 10–1000, com validação no controller e no serviço. Isso não é justificativa de correção, não integra cálculo de risco/visita e não é exigido para detecção automática de faltas. Dados inválidos retornam 422 com mensagens seguras. Há adaptação **local** no controller novo para JSON inválido, sem ecoar seu conteúdo nem alterar `ResourceExceptionHandler`; a revisão global permanece na Etapa 7.

### Integridade, migração e provas

V4 acrescenta a tabela operacional, FKs, índices, unicidade ator/chave e trigger append-only. Também fortalece aditivamente o check de sessão não nula da auditoria de grupo, porque CHECK SQL não rejeita por si só toda expressão indeterminada por NULL. **V3 não foi reescrita**; V1/V2 também permanecem intactas. Encaminhamento exige destino/descrição explicitamente não nulos no banco. Nenhuma migração cria consultas, visualizações, encaminhamentos ou justificativas do passado.

Ações individuais de alerta usam paciente → ator autenticado, compatível com o sentido paciente → usuário da agenda. O lock do ator serializa reutilização da chave entre requisições; uma constraint única também protege no banco. Consulta à lista integra sua transação existente: se falhar a gravação da auditoria, não confirma parcialmente o recálculo de risco que a lista já realizava. Nenhum evento operacional chama recálculo de frequência, cria reserva, adiciona presença/falta ou ocupa vaga. A reclassificação sob demanda do GET preexistente foi preservada e é distinguida da nova auditoria.

`BuscaAtivaAuditIntegrationTests`: **30 execuções** HTTP/JPA, cobrindo consulta distinta, três perfis de visualização, autoria/tempo/tipo/unidade forjados, rua/referência protegida, vermelho sem gatilho, pendências, resolução/inativação, encaminhamento formal, rejeições de recepção/escopo, USFs diferentes, paginação, paciente transferido, validação direta/HTTP, JSON sanitizado, idempotência, DML imutável, rollback após INSERT, rollback do recálculo da lista e repetição concorrente (duas confirmações HTTP para um único evento).

Ampliados os testes de responsabilidade com confirmação e inscrição retroativa pelo coordenador/colega (quatro execuções), e de leitura de auditoria de grupo com cinco perfis/escopos. Assim, as classes de responsabilidade e auditoria clínica passam de 23/22 para **27/27** execuções.

`ClinicalAuditMigrationTests` passa a **8 execuções**: cobre V0, V2 e V3 como entrada; verifica zero auditorias inventadas; testa nulos, FKs e estados impossíveis via SQL; registra por HTTP uma correção clínica, três frequências iniciais, consulta, visualização e encaminhamento formal; reabre a aplicação e restaura backup em destino isolado. Os quatro registros de auditoria (uma correção e três eventos operacionais) continuam acessíveis em três inicializações. As três frequências iniciais não viram correções auditadas. O arquivo desse ensaio passa a 15 atendimentos; a fixture principal de preservação continua com seus 12 originais. Reaplicação de migrações: zero.

Resultados: focado final de contratos/autorização/auditoria **84/84**, verify integrado antes do último check SQL **512/512** (54,551 s, término 19:01:24), focado com os checks finais e restauração **38/38** (25,170 s, término **19:04:39 de 10/11/2026**). O caso SQL adicional eleva o conjunto final esperado a 513; a B6 registrará o total efetivamente executado. A primeira rodada RNF17 revelou três diferenças de precisão de timestamp nos recibos e uma fixture afetada pela reclassificação legítima de outro paciente; precisão foi corrigida no código, e o teste da lista passou a isolar seus pacientes. Não foram apagados testes nem mudadas regras clínicas para obter sucesso.

**RNF17 não está integralmente concluído na interface.** O backend distingue e protege os eventos; o Angular atual não chama as rotas explícitas de apresentação/registro formal. Não se afirma que seus usuários já tiveram visualizações reais rastreadas automaticamente nem que houve entrega a outro serviço. A integração visual/operacional permanece na Etapa 8.

## Recomendações permanentes — Etapas 4–9

| Etapa | Pendência que permanece |
|---|---|
| 4 | Correção retroativa e no mesmo dia após o horário previsto, com relógio/fuso oficial, recálculo completo e preservação das regras assistenciais. Guarda temporal anterior não foi substituída nesta entrega. |
| 5 | Regularização de cancelamentos incorretos com justificativa, auditoria e sem reservas retroativas fictícias. A reativação genérica existente não é declarada fluxo definitivo. |
| 6 | Reconciliação autorizada de históricos ambíguos, inconsistências da migração e conflitos, preservando evidências e sem reescrever auditorias imutáveis. |
| 7 | Revisão global de erros de negócio, concorrência e infraestrutura; preservar diagnóstico e não mascarar falhas internas como conflito recuperável. |
| 8 | UI para justificativa, consulta de auditoria, reserva/frequência separadas, indicadores provisórios e registro real de visualização/encaminhamento. Preparar dados fictícios completos para demonstração; não chamar visualização por simples refresh da lista. |
| 9 | Validação de todos os resumos, relatórios e indicadores; testes Angular pendentes; CI remota; escolha do SGBD definitivo; migração, restauração, privilégio das credenciais e concorrência no banco escolhido. |


## B6 — validação final e resultado para revisão

### Resultados reais e preservação do baseline

**APROVADA PARA REVISÃO da Pré-Etapa 3 e da Etapa 3 no escopo backend especificado.** RNF17 possui suporte backend testado, com integração visual explicitamente pendente; não se declara sua conclusão integral na interface nem prontidão para produção. A compilação Angular exigida passou; suas falhas unitárias anteriores permanecem separadas e documentadas.

Os XMLs finais comprovam **513 casos em 29 classes**, sendo os **382 anteriores preservados e aprovados + 131 novos**. A comparação nominal, considerando somente o rename autorizado do cenário de colega da mesma unidade, encontra zero cenário anterior ausente. Não houve remoção, @Disabled ou skipTests. Os novos casos dividem-se em 19 da Entrega A e 112 da Etapa 3: 27 responsabilidade, 20 justificativas, 27 auditoria clínica/grupos/concorrência, 8 migração e 30 RNF17.

| Conjunto / execução | Executados | Aprovados | Falhas | Erros | Ignorados |
|---|---:|---:|---:|---:|---:|
| Baseline recebido — verify | 382 | 382 | 0 | 0 | 0 |
| Entrega A — verify | 401 | 401 | 0 | 0 | 0 |
| Responsabilidade/justificativa — verify | 444 | 444 | 0 | 0 | 0 |
| Auditoria clínica — verify | 472 | 472 | 0 | 0 | 0 |
| RNF17 antes do check SQL final — verify | 512 | 512 | 0 | 0 | 0 |
| Checks finais / persistência / RNF17 — focado | 38 | 38 | 0 | 0 | 0 |
| Final — mvnw test | 513 | 513 | 0 | 0 | 0 |
| Final — mvnw verify | 513 | 513 | 0 | 0 | 0 |

Comandos completos no backend: `bash ./mvnw test` e `bash ./mvnw verify`, com Java 21, settings/cache Maven da nuvem e sem desabilitar testes. `test` usou ambiente herdado; `verify` usou variáveis dev sintéticas para conferir também o isolamento dos testes diante dessas variáveis, sem precisar limpar manualmente o terminal. Material sintético não é fallback de configuração da aplicação.

- `final-test`: BUILD SUCCESS, 53.761 s, término **10/11/2026 19:09:56 America/Sao_Paulo**.
- `final-verify`: BUILD SUCCESS, 53.596 s, término **10/11/2026 19:11:22 America/Sao_Paulo**.

Detalhamento do verify final:

| Suíte | Executados | Aprovados | Falhas | Ignorados |
|---|---:|---:|---:|---:|
| AgendaCombinedTests | 4 | 4 | 0 | 0 |
| AgendaConcurrencyTests | 18 | 18 | 0 | 0 |
| AgendaConfiguredGridTests | 3 | 3 | 0 | 0 |
| AgendaGridTests | 33 | 33 | 0 | 0 |
| AgendaLegacyMigrationTests | 9 | 9 | 0 | 0 |
| AgendaRescheduleTests | 10 | 10 | 0 | 0 |
| AttendanceCorrectionIntegrityTests | 45 | 45 | 0 | 0 |
| BuscaAtivaAuditIntegrationTests | 30 | 30 | 0 | 0 |
| BuscaAtivaSelectionTests | 19 | 19 | 0 | 0 |
| BuscaativaApplicationTests | 1 | 1 | 0 | 0 |
| CanonicalAppointmentTests | 18 | 18 | 0 | 0 |
| ClinicalAuditIntegrationTests | 27 | 27 | 0 | 0 |
| ClinicalAuditMigrationTests | 8 | 8 | 0 | 0 |
| ClinicalAuthorizationTests | 27 | 27 | 0 | 0 |
| ClinicalJustificationTests | 20 | 20 | 0 | 0 |
| GlobalConcurrencyHandlerReviewTests | 25 | 25 | 0 | 0 |
| H2ProfileSecurityTests | 4 | 4 | 0 | 0 |
| HistoricalAttendanceReviewTests | 33 | 33 | 0 | 0 |
| HomeVisitRecalculationTests | 14 | 14 | 0 | 0 |
| IsolamentoUnidadeTests | 65 | 65 | 0 | 0 |
| JwtKeyConfigurationTests | 14 | 14 | 0 | 0 |
| MigracaoUnidadeTests | 1 | 1 | 0 | 0 |
| MigratedAgendaHttpTests | 5 | 5 | 0 | 0 |
| MigratedIndicatorsInitializationTests | 2 | 2 | 0 | 0 |
| PainelHistoricoEscopoTests | 1 | 1 | 0 | 0 |
| ProfissionaisEscopoTests | 47 | 47 | 0 | 0 |
| SecurityP01P02Tests | 15 | 15 | 0 | 0 |
| UsuarioUnidadeTests | 13 | 13 | 0 | 0 |
| JwtKeyPolicyTests | 2 | 2 | 0 | 0 |

### Concorrência e rollback

Cada execução final contém **47 disputas / 114 operações**, com threads, conexões e transações independentes e verificação de linhas, vínculos, versões, contadores e históricos persistidos. Não se somam retestes para aumentar esses números.

| Execução / grupo | Disputas | Operações | Confirmações HTTP | Rejeições |
|---|---:|---:|---:|---:|
| final-test: RNF17 | 1 | 2 | 2 | 0 |
| final-test: Integrados | 3 | 8 | 2 | 6 |
| final-test: Etapa 3 clínica | 4 | 8 | 4 | 4 |
| final-test: Visita Etapa 2 | 2 | 4 | 4 | 0 |
| final-test: P04 | 17 | 52 | 20 | 32 |
| final-test: Etapa 1 | 19 | 38 | 20 | 18 |
| final-test: P05 | 1 | 2 | 1 | 1 |
| final-test: TOTAL | 47 | 114 | 53 | 61 |
| final-verify: RNF17 | 1 | 2 | 2 | 0 |
| final-verify: Integrados | 3 | 8 | 2 | 6 |
| final-verify: Etapa 3 clínica | 4 | 8 | 4 | 4 |
| final-verify: Visita Etapa 2 | 2 | 4 | 4 | 0 |
| final-verify: P04 | 17 | 52 | 19 | 33 |
| final-verify: Etapa 1 | 19 | 38 | 22 | 16 |
| final-verify: P05 | 1 | 2 | 1 | 1 |
| final-verify: TOTAL | 47 | 114 | 54 | 60 |

Resultados variam conforme a serialização permitida: correção e remarcação sem versão exigida podem ambas confirmar quando a remarcação vem primeiro, preservando a frequência do original SUBSTITUIDA; com versão obsoleta, somente a operação válida confirma. Repetição concorrente RNF17 confirma dois recibos HTTP e persiste **um único evento**. Nenhum erro técnico inesperado foi aceito como rejeição válida.

Rollback foi comprovado após gravações reais, inclusive INSERT da auditoria, falha no histórico, segundo participante do lote e falha na auditoria da consulta à lista. O snapshot inclui todas as tabelas; nenhuma frequência, indicador, revisão ou auditoria parcial é confirmada. Os testes antigos de correção versus criação, colisões, capacidade, versão e isolamento permanecem aprovados.

### Frontend — build aprovado, testes anteriores ainda reprovados

Node **24.19.0**, npm **11.6.2** selecionado por npx conforme packageManager, Angular 21. No diretório `frontend/sentinela-pet`:

| Comando | Resultado |
|---|---|
| npm ci | Código 0 |
| npm run build | Código 0; 4,783 s, término 19:11:30 de 10/11/2026 |
| npm test -- --watch=false | Código 1; 104 testes: 97 aprovados, 7 falhas, zero ignorados; mais 5 erros não tratados |

Os cinco erros não tratados são adicionais ao resultado dos 104 casos, não cinco novos testes. Dos 28 arquivos, 22 passaram e seis falharam. As falhas reproduzem o padrão já registrado como Q01–Q04: título antigo no teste de App (um caso), ausência de ActivatedRoute nos testes de MainLayout/DetalheProfissional/Inicio/Sidebar (quatro), mock sem buscarDetalhe em DetalhePaciente (dois), e cinco requisições não tratadas a /api/usuarios/me. Fontes, testes e lockfile Angular são idênticos ao estado recebido; nenhum teste foi alterado para ocultar esses resultados. Correção permanece na Etapa 9.

O bundle manteve **847,77 kB**, com aviso de 347,77 kB acima do orçamento de 500 kB. O aviso não foi desabilitado. Não foram redesenhadas telas nem implementadas justificativas/visualizações RNF17 na UI; build verde não equivale a validação ponta a ponta desses novos fluxos.

A primeira instalação no sandbox não alcançou o proxy herdado e foi interrompida; os mesmos comandos foram repetidos fora dessa restrição, usando o proxy e a política de rede do ambiente. Isso foi um impedimento de infraestrutura local, não uma falha atribuída ao Angular. O mesmo ajuste foi necessário para a leitura Git remota; nenhuma política de rede/credencial foi enfraquecida.

### Migrador empacotado, preservação e retorno seguro

O jar gerado pelo verify foi realmente executado com `PropertiesLauncher`, sobre outra cópia sintética do schema antigo: preparação, primeira execução, repetição e consultas retornaram código 0. **Quatro migrações na primeira execução; zero na segunda.** Conferência SQL: 12 atendimentos, quatro pendentes, três ocupações, zero auditorias clínicas, zero eventos RNF17 criados pelo backfill e quatro operações protegidas por triggers (UPDATE/DELETE das duas tabelas). A migração não inventa eventos anteriores.

Comando, a partir da raiz do repositório, somente em cópia H2 isolada com escritores suspensos e `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` explicitamente fornecidos no ambiente:

```bash
java -Dloader.main=com.pet.buscaativa.migration.AgendaMigrationTool \
  -cp backend/buscaativa/target/buscaativa-0.0.1-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher
```

O programa exige arquivo H2, DB_CLOSE_DELAY=0 e schema legado reconhecido, verifica origem canônica/versionada e as proteções finais. Não inicializa aplicação web nem migra automaticamente na inicialização. V1/V2 permanecem sem alteração; V3 também permaneceu idêntica ao seu commit, e o fortalecimento posterior foi aditivo em V4.

Os ensaios preservam integralmente IDs, relações, códigos legados, campos canônicos, flags, versões, históricos e revisões anteriores. O teste real de aplicação conserva uma correção e três eventos de alerta após três inicializações/restauração, mais as três reservas sintéticas com frequência inicial (15 atendimentos nessa cópia). A fixture fixa de preservação permanece com seus 12 dados originais. Ambos os tipos de auditoria continuam protegidos após restaurar.

Antes de ensaiar, guardar backup anterior e inventário; após iniciar escritas novas, guardar também backup/export **mais recente**, incluindo dados clínicos, auditorias, revisões e histórico Flyway. Testar restauração em destino separado e usar binário compatível. Voltar somente ao backup anterior perderia escritas posteriores; executar o binário antigo também perderia as novas garantias de responsabilidade/justificativa/auditoria. **Downgrade semântico completo não foi garantido**. Não apagar tabelas/triggers, usar clean/repair automático ou descartar histórico para simular retorno. H2 pode confirmar DDL separadamente; falha parcial exige diagnóstico e recuperação pela cópia verificada, com aplicação bloqueada até estrutura coerente.

### Preservações, mudanças intencionais e limites

| Tema | Resultado comprovado |
|---|---|
| P01/P02 | JWT, chaves e console H2 preservados; fontes de segurança não modificadas. |
| P03 | Grade, turnos, oferta, capacidade e bloqueios continuam protegidos. |
| P04 | Locks/versões e colisões individuais preservados; novos lotes de grupo e recibos têm atomicidade testada. |
| P05 | Mesmo paciente, vínculo original/sucessor, falta original e concorrência de remarcação preservados. |
| Etapa 1 | Correção histórica autorizada continua independente da oferta atual, sem aceitar colisão real da agenda. |
| Etapa 2 | Códigos 1–6 e reserva/frequência canônicas mantidos; desconhecimento legado continua marcado, sem clínica inventada. |
| Indicadores/visita | Limites anteriores, faltas, última presença, grupos, risco e decisão cronológica de visita continuam testados. Justificativa/auditoria não entram no cálculo. |
| Histórico/Envers | Continuam com finalidades próprias; auditorias novas não são contadas como presença/falta. |
| Unidades | Escopo inclusive USF específica, administrador, grupo inteiro e paciente transferido conferidos. |
| Mudanças aprovadas | Lista inclui gatilho confirmado sem forçar vermelho; responsabilidade por atendimento; motivo em correções reais; auditoria append-only; eventos explícitos e distintos RNF17. |
| Decisões/fluxos futuros | Reconciliação de ambiguidades, cancelamentos incorretos, política temporal definitiva e interface permanecem nas Etapas 4–9. |

Implantação permanece bloqueada: SGBD definitivo não escolhido/validado, concorrência/migração/restauração e privilégios desse banco ainda necessários, CI remota não executada, interface das novas operações incompleta e falhas Angular anteriores abertas. Flyway 11.14.1/Boot 4.0.6/H2 2.4.240 foram exercitados aqui; o aviso de H2 acima da última versão oficialmente verificada pelo Flyway (2.3.232) permanece visível. Nenhuma execução foi em banco real.

### Critérios de aceitação

| Critérios do pedido | Evidência / situação |
|---|---|
| 1–2: busca ativa/RF22 | 19 casos HTTP/JPA/Excel da Entrega A; risco e visita independentes. |
| 3–5: responsável/admin/recepção | 27 casos de autorização; origem autenticada e escopo específico. |
| 6–8: motivo, legado e grupos | 20 de justificativas e cobertura dos caminhos alternativos; sem bypass por cadastro/estado de resposta. |
| 9–11: auditoria, fatos e histórico | 27 de auditoria clínica, testes antigos e snapshots; registros distintos e append-only. |
| 12: RNF17 seguro | 30 casos; backend pronto, UI explicitamente pendente e sem falsa visualização/entrega. |
| 13–14: atomicidade/indicadores | Rollback completo, 47 disputas reais e regressões de indicadores/visita. |
| 15–16: baseline/novos | 382 + 131 = 513, sem casos antigos ausentes ou ignorados. |
| 17: migrações | Nove testes anteriores + oito novos; arquivos H2, versões, reabertura, restauração e CLI empacotada. |
| 18: Angular | ci/build aprovados; limitações de UI e testes anteriores reprovados explicitadas. |
| 19–20: dados/Git | Somente sintéticos; branch exigida; sem merge/PR/force push. Confirmação remota na resposta final. |

### Arquivos alterados e integridade de escopo

Inventário completo: **48 arquivos**. Conferência SHA-256 preserva **149 arquivos protegidos**, incluindo todo o frontend, lockfile, CI, segurança/JWT, handler global, enum/conversor legados, V1/V2 e fixture SQL antiga. Nenhum arquivo rastreado foi removido. O diff completo foi revisado e passou em git diff --check.

- [backend/buscaativa/pom.xml](../backend/buscaativa/pom.xml)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/controllers/AgendamentoController.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/controllers/AgendamentoController.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/controllers/AuditoriaBuscaAtivaController.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/controllers/AuditoriaBuscaAtivaController.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/controllers/AuditoriaCorrecaoController.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/controllers/AuditoriaCorrecaoController.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/AuditoriaBuscaAtiva.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/AuditoriaBuscaAtiva.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/AuditoriaCorrecaoFrequencia.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/AuditoriaCorrecaoFrequencia.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/AuditoriaBuscaAtivaDTO.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/AuditoriaBuscaAtivaDTO.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/AuditoriaCorrecaoDTO.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/AuditoriaCorrecaoDTO.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/CorrigirFrequenciasGrupoDTO.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/CorrigirFrequenciasGrupoDTO.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/EncaminhamentoAlertaDTO.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/EncaminhamentoAlertaDTO.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/JustificativaCorrecaoDTO.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/JustificativaCorrecaoDTO.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/VisualizacaoAlertaDTO.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/VisualizacaoAlertaDTO.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/enums/TipoEventoBuscaAtiva.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/enums/TipoEventoBuscaAtiva.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/migration/AgendaMigrationTool.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/migration/AgendaMigrationTool.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/migration/AuditAppendOnlyTrigger.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/migration/AuditAppendOnlyTrigger.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/migration/AuditoriaSchemaGuard.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/migration/AuditoriaSchemaGuard.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/AuditoriaBuscaAtivaRepository.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/AuditoriaBuscaAtivaRepository.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/AuditoriaCorrecaoRepository.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/AuditoriaCorrecaoRepository.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/GrupoTerapeuticoRepository.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/GrupoTerapeuticoRepository.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/PacienteRepository.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/PacienteRepository.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/SessaoGrupoParticipanteRepository.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/SessaoGrupoParticipanteRepository.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/SessaoGrupoRepository.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/SessaoGrupoRepository.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/AgendamentoService.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/AgendamentoService.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/AuditoriaBuscaAtivaService.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/AuditoriaBuscaAtivaService.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/AuditoriaCorrecaoService.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/AuditoriaCorrecaoService.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/AutorizacaoClinicaService.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/AutorizacaoClinicaService.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/PoliticaJustificativaCorrecao.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/PoliticaJustificativaCorrecao.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/AgendamentoServiceImpl.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/AgendamentoServiceImpl.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/GrupoTerapeuticoServiceImpl.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/GrupoTerapeuticoServiceImpl.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/PacienteServiceImpl.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/PacienteServiceImpl.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/PainelServiceImpl.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/PainelServiceImpl.java)
- [backend/buscaativa/src/main/resources/agenda-migration/h2/V3__auditoria_correcao_frequencia.sql](../backend/buscaativa/src/main/resources/agenda-migration/h2/V3__auditoria_correcao_frequencia.sql)
- [backend/buscaativa/src/main/resources/agenda-migration/h2/V4__auditoria_eventos_busca_ativa.sql](../backend/buscaativa/src/main/resources/agenda-migration/h2/V4__auditoria_eventos_busca_ativa.sql)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/AgendaConcurrencyTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/AgendaConcurrencyTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/AgendaLegacyMigrationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/AgendaLegacyMigrationTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/AttendanceCorrectionIntegrityTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/AttendanceCorrectionIntegrityTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/BuscaAtivaAuditIntegrationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/BuscaAtivaAuditIntegrationTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/BuscaAtivaSelectionTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/BuscaAtivaSelectionTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/CanonicalAppointmentTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/CanonicalAppointmentTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/ClinicalAuditIntegrationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/ClinicalAuditIntegrationTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/ClinicalAuditMigrationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/ClinicalAuditMigrationTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/ClinicalAuthorizationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/ClinicalAuthorizationTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/ClinicalJustificationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/ClinicalJustificationTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/HistoricalAttendanceReviewTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/HistoricalAttendanceReviewTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/HomeVisitRecalculationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/HomeVisitRecalculationTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/MigratedAgendaHttpTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/MigratedAgendaHttpTests.java)
- [docs/etapa3-permissoes-justificativas-auditoria.md](../docs/etapa3-permissoes-justificativas-auditoria.md)
- [docs/pre-etapa3-correcao-busca-ativa.md](../docs/pre-etapa3-correcao-busca-ativa.md)

### Commits e publicação

Base inicial: `6ad7e0c18b59e8292e7748a358cf92ec3fe7ed38`. Branch única: `correcao-fluxos-assistenciais-sibraps`.

- `e514a85` — fix(busca-ativa): inclui pacientes com gatilho confirmado
- `6f0f4cc` — docs(seguranca): mapeia responsabilidades e contratos da etapa 3
- `5bcb51b` — fix(seguranca): exige responsabilidade por atendimento e sessao
- `d60f73b` — feat(frequencia): exige justificativa em correcoes individuais e de grupo
- `2443740` — feat(auditoria): persiste correcoes imutaveis com migracao V3
- `89d5287` — feat(rnf17): audita consultas visualizacoes e encaminhamentos explicitos

O commit de fechamento da B6 contém este resultado final. Seu SHA e a confirmação do push são apresentados na resposta final; o arquivo não contém o próprio hash futuro. Publicação somente por push normal desta branch. Referências remotas verificadas antes de publicar: main `967d5162dd543ec9005608739128423903e75e30`; P03/P04/P05 `05ee61a5123dfe4e914d7c2f5f486f7ecf310164`; branch de trabalho ainda na base recebida. Não houve merge, PR automático, force push, exclusão de branch ou execução em dados reais.

Logs, XMLs, sumários e cópias sintéticas de ensaio ficaram em `/tmp/sibraps-etapa3/`, sem serem incorporados ao repositório. A comparação de casos antigos e a agregação de concorrência foram calculadas dos XMLs/logs finais, não estimadas por contagem de métodos.
