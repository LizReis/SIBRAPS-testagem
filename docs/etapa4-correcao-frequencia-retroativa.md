# Etapa 4 — correção de frequência, inclusive retroativa

## Contexto, base e diagnóstico antes da implementação

Repositório LizReis/SIBRAPS-testagem; branch correcao-fluxos-assistenciais-sibraps; commit inicial 1b0735d1dad5894737ec92ac691ecb915d339ae9, checkout limpo, ancestralidade e commits das Etapas 1–3.1 conferidos. Relatórios anteriores e plano consultados. Baseline efetivamente reproduzido: mvnw verify, **579 testes, zero falhas/erros/ignorados**, sem skipTests. Evidências em /tmp/sibraps-etapa4/baseline.

O Clock atual é systemDefaultZone; agendamentos usam LocalDate.now sem injeção. AtualizarStatus valida a data, não o horário; corrigirFrequencias de grupo verifica REALIZADA mas não o horário. Grupos já utilizam Clock para confirmação e marcação provisória. O histórico individual reutiliza o evento clínico, o responsável do atendimento e Envers; a auditoria guarda o executor e instante UTC. Em grupos, o campo profissional do histórico ainda recebe o executor: deve passar a representar o responsável histórico nas operações conhecidas, mantendo autoria na auditoria e preservando eventos antigos sem reescrita em massa.

Caminhos de frequência: PATCH individual/status e duas sobrecargas do serviço; cadastro POST só aceita reserva sem frequência; PATCH de presença provisória de grupo; POST confirmação de ocorrência; POST inscrição retroativa inicial; PATCH frequencias de grupo; status de grupo não confirma realização; entidades/fixtures/migrações não são rotas públicas. Correção individual FALTOU→PRESENTE já evita oferta atual e mantém colisões P04. PRESENTE→FALTOU não consulta oferta. A origem SUBSTITUIDA com frequência conhecida já admite correção sem reabrir reserva. Cancelamento genérico de fatos conhecidos está bloqueado pela 3.1.

Plano: política temporal única com configuração tipada agenda.tempo.fuso, padrão America/Bahia; comparação de datas/horas assistenciais locais, sem reescrever horários ou atribuir UTC ao legado. Registro inicial em >= horário; correção somente > horário. Dados ausentes ou horário local ambíguo/inexistente ficam protegidos para reconciliação, sem inventar horário. Endpoint específico com destino PRESENTE/FALTOU, versão obrigatória e justificativa; aplicação central compartilhada pelo legado, mantendo versão opcional do contrato antigo e validação obrigatória quando enviada, antes de idempotência. Locks individuais paciente→profissional→atendimento e de grupos permanecem.

Colisões de oferta e assistenciais serão diferenciadas. O mínimo para reconciliação será uma resposta estruturada e segura, com código/tipo e somente a identidade do atendimento autorizado; não será criada fila nem persistido um fato clínico para representar recusa. A estrutura de migração pendente identifica ambiguidade de legado, não é fila de colisões. O rollback não poderá apagar uma auditoria efetiva porque nenhuma será criada para operação recusada. Etapa 6 deverá tratar as colisões mediante revisão autorizada. Grupos no mesmo instante serão testados segundo a proteção existente de participação do paciente; não será redesenhado o agendamento de grupos.

Não alterar V1–V6, handler global, limites de risco, política de visita, RF22, RNF17 ou telas. Não iniciar Etapa 5 ou resolver reconciliação. Produção será ajustada apenas nos pontos necessários; testes antigos que pressupõem dia inteiro disponível terão relógio explícito, mantendo os cenários e acrescentando fronteiras reais.

## 4A — política temporal comprovada

Clock institucional injetável, padrão America/Bahia, configurável por agenda.tempo.fuso (AGENDA_TEMPO_FUSO). Registro inicial aceita exatamente o horário; correção exige instante local estritamente posterior. Não há prazo máximo retroativo. Clock.fixed cobre ontem, meses, antes/exato/depois, dia seguinte, meia-noite UTC/local, relógio com outro ZoneId, fuso padrão da máquina diferente, propriedade inválida e datas/horas incompletas ou ambíguas em transições históricas de verão da Bahia. Nenhum horário é convertido/persistido como UTC; auditorias continuam Instant UTC.

A mesma política atende individuais, presença provisória, confirmação real e correção de grupo. O relógio institucional também orienta limites de datas da agenda; consumidores existentes de Clock (indicadores, grupos, auditoria, relatórios) passam a receber seu fuso explícito. Datas de cadastro antigas e demais operações externas ao fluxo não são regravadas.

Os fixtures de AgendaIntegritySupport agora usam explicitamente Clock.fixed no fim do dia assistencial representado pelos testes. Isso mantém seus cenários de frequência passada, independentemente da hora em que a suíte executa, sem remover validação; novas suítes controlam o relógio antes/exatamente/depois. A configuração é restrita ao classpath e propriedade dos testes, não entra em produção nem contamina aplicações persistidas de validação.

Execução 4A: 603 casos = 579 anteriores + 24 novos, zero falhas/erros/ignorados. Nenhuma migração criada.

## 4B — contrato específico e aplicação compartilhada

PATCH /api/agendamentos/{id}/frequencia/correcao recebe CorrigirFrequenciaDTO: novaFrequencia PRESENTE/FALTOU, version inteira não negativa obrigatória e justificativa entre 10 e 2000 caracteres, validada pela política existente (inclui rejeição de genéricos e normalização). Exemplo:

```json
{"novaFrequencia":"PRESENTE","version":4,"justificativa":"Presença confirmada após conferência do registro assistencial."}
```

Retorna 200 com AgendamentoDTO existente: id, situação da reserva preservada, frequenciaAssistencial e version resultante. Ator, responsável, instante e campos da reserva não são argumentos do comando. NR como destino é 422; valores fora do enum não são JSON válido para esse campo (400). Corpo ausente/ilegível segue convenção existente. NR de origem é 409; CANCELADA permanece recusada (422, Etapa 5); SUBSTITUIDA com frequência conhecida é corrigível sem reabertura. Migração pendente continua bloqueada.

Novo endpoint e duas sobrecargas legadas usam uma aplicação central transacional: paciente→profissional→atendimento, escopo e responsável, estado, versão antes de idempotência, política temporal, motivo, conflito, recálculo, histórico e auditoria. Novo endpoint exige versão e motivo também em no-op; legado conserva versão opcional e no-op sem motivo, com escopo/autorização/versão informada/tempo sempre validados. Uma alteração real exige motivo em ambos. Versão obsoleta nunca vira sucesso só por coincidir com o destino atual.

Matriz: responsável e administrador local autorizados; colega/recepção 403, externo 404 e não autenticado 401. USFs com IDs diferentes não compartilham acesso. Recepção mantém registro inicial via status, sem justificativa artificial e com fronteira >= horário. Angular precisará enviar version e motivo ao novo endpoint na Etapa 8; não foi criado bypass visual.

Histórico individual mantém profissional do atendimento, evento e identidade. Em novas escritas de frequência de grupo, profissional passa a ser o responsável histórico, enquanto executor permanece na auditoria e Envers. Eventos antigos não são reescritos por startup/migração; a correção legítima atualiza sua representação na própria transação. Inscrição retroativa inicial também valida tempo/autorização das sessões realizadas antes de construir a cronologia, protegendo dados incompletos em vez de falhar com NPE.

Validação 4B: 125 testes focados aprovados, incluindo 38 novos de contrato, permissões, direções, idempotência, versão, payload adulterado e rollback após frequência/histórico/INSERT real de auditoria. Todos sem falhas/erros/ignorados.

## 4C — independência da oferta

Quatorze novos casos persistidos, nas duas direções, confirmaram a independência de ausência de oferta, capacidade zero/reduzida/esgotada, bloqueio posterior, turno diferente e grade alterada. Identidade, data, hora, turno, quantidade de atendimentos e histórico são preservados; apenas a frequência, versão, indicadores e auditoria da correção mudam. Essa separação já estava correta desde a Etapa 1; não se removeu validarVaga de criação/remarcação nem se reimplementou o comportamento aprovado. Os casos antigos de nova reserva bloqueada, sem vaga/fora da grade e remarcação indisponível continuam obrigatórios na regressão integrada.

## 4D — conflitos identificáveis e concorrência

409 de colisão real acrescenta codigo=CONFLITO_ASSISTENCIAL_PENDENTE_RECONCILIACAO, tipoConflito (PROFISSIONAL, PACIENTE, PARTICIPACAO_GRUPO ou SESSAO_PROFISSIONAL), agendamentoId autorizado e caminho da requisição. Mantém error de conflito de negócio e orientação de regularização do contrato anterior. Não retorna nome, CPF, CNS, identidade ou detalhes do outro paciente/atendimento. Versão obsoleta continua conflito de atualização separado; acesso externo é 404; ausência/ambiguidade de horário usa HORARIO_ASSISTENCIAL_PENDENTE_RECONCILIACAO; migração pendente conserva sua sinalização canônica. Handler global não foi alterado: o adaptador novo é restrito aos controllers de agendamento/grupo e à exceção específica.

São reutilizadas as consultas individuais P04 e a regra existente de participação simultânea em grupo não cancelado. Sessão simultânea do profissional considera responsável consolidado para REALIZADA e coordenador vigente para AGENDADA; não presume o dono de sessão ambígua nem substitui-o pelo coordenador atual. Nenhuma outra frequência, reserva, indicador ou auditoria é alterada numa recusa. Cancelada sem frequência, substituída não ocupante, faltas não ocupantes e horários diferentes não produzem falsos conflitos individuais.

A identificação mínima é a resposta estruturada, reproduzível em nova tentativa autorizada sobre o atendimento enquanto o conflito existir. Não é uma fila persistida, auditoria de correção concluída ou resolução automática. Não há REQUIRES_NEW nem nova migração. Etapa 6 deverá examinar os fatos concorrentes e a evidência histórica por acesso autorizado. A detecção nesta etapa não redesenha a política global de criação/inscrição de grupos nem certifica exclusividade cruzada em todos os fluxos futuros de grupo/agenda.

Os 21 novos casos de colisão e 15 de concorrência passaram, inclusive mesma versão, no-op simultâneo, profissional não responsável, remarcação, cancelamento, criação, capacidade e bloqueio. Conexões/transações independentes e estado persistido foram conferidos. Correção factual não perde validade porque capacidade/bloqueio mudou; a outra operação administrativa pode ser recusada se a presença já ocupa vaga. Nenhum erro técnico de lock/deadlock foi aceito como conflito funcional. Responsabilidade histórica de sessões foi conferida também na detecção de sobreposição, após troca de coordenação.

## 4E — indicadores, histórico, grupos e persistência

Sete novos cenários usam registro inicial real, P05 real e a rota específica nas correções: origem SUBSTITUIDA mantém vínculo/flag e passa a PRESENTE; sucessora corrigida limpa alerta; outro par posterior mantém AMARELO+visita; presença individual/grupo posterior determina a última presença e zera sequência; três faltas independentes dão VERMELHO sem visita; alternâncias sucessivas mantêm contador correto, três auditorias para três mudanças e nenhuma no no-op. Quantidades de atendimentos e eventos clínicos permanecem iguais. Busca ativa acompanha o resultado sem contar auditoria como atendimento.

Dezessete novos casos de grupos cobrem a mesma fronteira estrita das correções, registro inicial inclusivo, responsável antigo após transferência, administrador versus responsável, recepção/externos, dois participantes, lote inválido, falha após segundo INSERT de auditoria e horário ausente também na inscrição retroativa. Nenhuma agenda individual fictícia é criada. Profissional do histórico é o responsável da sessão; executor é identificado na auditoria. A regra histórica da 3.1 permanece.

Dois novos ensaios persistidos usam a fixture SQL antiga intocada e migrador V1–V6 existente. Um confirma a rota específica sobre frequência conhecida, fecha/reabre e restaura o backup mais recente em outro arquivo: três inicializações validate, auditoria/versões/snapshots preservados, zero migrações na repetição. O outro recusa a rota específica para frequência migrada desconhecida, mantém indicadoresPendentes e snapshots integrais, sem inventar frequência. Esses casos, somados aos grupos, passaram em 26 testes focados.

Não foi necessária alteração de schema ou nova migração: V1–V6, índices, constraints, Envers e auditorias antigas permanecem. Só a representação de uma correção efetivamente autorizada é atualizada, na mesma transação da auditoria e do recálculo. A temporalidade da nova rota recebeu sete casos HTTP adicionais (antes/exato/depois, meia-noite, meses e relógio do cliente falsificado), compartilhando a política central.

## Revisão final do relógio nos indicadores

A inspeção após a primeira suíte completa (724/724) identificou duas consultas de PacienteServiceImpl e o resumo de dias sem presença no histórico que ainda usavam LocalDate.now da máquina. Foram alinhados ao Clock institucional já empregado no recálculo, sem mudar limites de risco, frequências ou horários persistidos. Um novo teste HTTP fixa 00h10 UTC/21h10 Bahia e comprova o dia assistencial e o resumo correto. A suíte completa foi repetida: **725 testes, zero falhas/erros/ignorados**. Datas de outras rotinas administrativas e timestamps locais de eventos preexistentes não foram reescritos; uma revisão global de apresentações permanece nas etapas futuras.

## 4F — resultados finais executados

Java 21.0.13, Maven Wrapper, Spring Boot 4.0.6 e H2 2.4.240. Foram executados a partir de backend/buscaativa, com settings/cache e JAVA_HOME do ambiente:

```bash
bash ./mvnw -s /workspace/.cache/sibraps/maven-settings.xml -B -Dmaven.repo.local=/workspace/.cache/sibraps/m2/repository test
bash ./mvnw -s /workspace/.cache/sibraps/maven-settings.xml -B -Dmaven.repo.local=/workspace/.cache/sibraps/m2/repository verify
```

Sem skipTests ou testes desabilitados. O test final usa ambiente herdado; verify também comprova isolamento com SPRING_PROFILES_ACTIVE=dev e credenciais sintéticas fortes no processo. Mockito exigiu anexar o agente Java fora da restrição do sandbox, sem enfraquecer as configurações de segurança. O verify produziu o jar empacotado.

| Conjunto | Executados | Aprovados | Reprovados | Ignorados |
|---|---:|---:|---:|---:|
| Baseline inicial verify | 579 | 579 | 0 | 0 |
| Casos anteriores retidos | 579 | 579 | 0 | 0 |
| Novos: tempo unitário e configuração | 14 | 14 | 0 | 0 |
| Novos: tempo HTTP e resumo institucional | 18 | 18 | 0 | 0 |
| Novos: contrato, permissões, auditoria e rollback individual | 38 | 38 | 0 | 0 |
| Novos: independência de oferta | 14 | 14 | 0 | 0 |
| Novos: identificação de colisões | 21 | 21 | 0 | 0 |
| Novos: concorrência individual | 15 | 15 | 0 | 0 |
| Novos: tempo, lote, autorização e rollback de grupo | 17 | 17 | 0 | 0 |
| Novos: indicadores e P05 | 7 | 7 | 0 | 0 |
| Novos: persistência/restauração e ambiguidade migrada | 2 | 2 | 0 | 0 |
| TOTAL de novos casos | 146 | 146 | 0 | 0 |
| mvnw test final | 725 | 725 | 0 | 0 |
| mvnw verify final | 725 | 725 | 0 | 0 |
| Angular unitários | 104 | 97 | 7 | 0 |

As linhas são recortes e execuções repetidas, não somar todos os conjuntos. Comparação por classe e nome dos XMLs Surefire: os mesmos **725 casos** nas duas execuções finais, **579 anteriores presentes, nenhum ausente e 146 novos**. Há 43 classes de teste executadas. Nenhum cenário antigo foi removido. A expectativa temporal antiga era aceitar todo o dia; os fixtures antigos agora representam explicitamente depois do horário, enquanto os novos testam a fronteira. Nenhuma validação foi desativada para preservar contagens.

| Execução | Disputas | Operações | Confirmadas | Rejeitadas |
|---|---:|---:|---:|---:|
| test final | 68 | 156 | 82 | 74 |
| verify final | 68 | 156 | 85 | 71 |

São 53 disputas anteriores e 15 novas. A serialização permitida explica a diferença de resultados. Cada operação usa transação e conexão próprias, com conferência posterior de versões, reserva/frequência, auditorias e indicadores. Incluem correção de grupo × transferência, duas correções de lote, individual × versão/criação/remarcação/cancelamento/capacidade/bloqueio e repetição simultânea. Erros técnicos inesperados não contam como rejeição funcional. Locks não foram substituídos por synchronized e nenhuma auditoria clínica usa REQUIRES_NEW.

### Cobertura das matrizes solicitadas

| Matriz | Evidências |
|---|---|
| T-01 a T-12 | AttendanceTimePolicyTests e AttendanceTemporalHttpTests: Clock.fixed, limites, fuso, corpo adulterado, recepção, informação temporal incompleta. |
| CF-01 a CF-16 | SpecificFrequencyCorrectionTests, tempo HTTP e indicadores: ambas as direções, no-op, NR/CANCELADA/SUBSTITUIDA, escopo/USF, motivo, versão, legado e chamadas diretas. |
| AG-01 a AG-12 | HistoricalCorrectionOfferTests e FrequencyCollisionIdentificationTests, somados aos casos antigos de grade, capacidade, bloqueios e P05 que permanecem presentes. |
| IN-01 a IN-12 / E-01 a E-07 | SpecificCorrectionIndicatorTests e SpecificCorrectionPersistenceTests, HomeVisitRecalculationTests e demais regressões antigas de cronologia e busca ativa. |
| AU-01 a AU-12 | Contrato específico, concorrência, lotes, persistência e ClinicalAuditIntegrationTests anteriores: ator/responsável, versões, falhas após escrita real/INSERT de auditoria e reabertura/restauração. |
| Grupos | GroupFrequencyTemporalTests e os 23 casos anteriores de GroupHistoricalResponsibilityTests, incluindo USFs, nova/antiga coordenadora, responsável transferido de unidade e migração histórica. |

### Contrato de resposta e diferenciação dos erros

Recorte ilustrativo de AgendamentoDTO após corrigir com version=4:

```json
{"id":42,"situacaoAtendimento":"PRESENTE","situacaoReserva":"AGENDADA","frequenciaAssistencial":"PRESENTE","version":5,"migracaoPendente":false,"ocupaVaga":true}
```

Os demais campos do DTO existente continuam presentes. No caso SUBSTITUIDA, a situação da reserva e a projeção legada REMARCADO_ORIGEM permanecem, mesmo com frequência PRESENTE.

| Situação | Resultado |
|---|---|
| Oferta atual alterada, sem colisão | Correção autorizada após horário, sem consulta à oferta como condição factual. |
| Colisão real | 409 com código/tipo estruturados e atendimento autorizado; nenhum fato clínico/auditoria de sucesso inserido. |
| Versão obsoleta | 409 de atualização, antes de idempotência e sem mudanças. |
| Não responsável/recepção | 403, sem alteração; unidade externa 404, sem expor dados. |
| Histórico ambíguo | Migração pendente e motivo canônico preservados; 409 sem inventar frequência. |
| Horário ausente/ambíguo/inexistente | 409 HORARIO_ASSISTENCIAL_PENDENTE_RECONCILIACAO. |
| Antes ou exatamente no horário de correção | 422; registro inicial exatamente no horário permanece permitido. |
| Cancelamento incorreto anterior | Continua sem regularização nesta rota, reservado à Etapa 5. |

### Angular

Node 24.19.0 e npm 11.6.2, conforme packageManager. Executados npm ci, npm run build e npm test -- --watch=false: códigos **0, 0 e 1**. Build manteve 847,77 kB e aviso de 347,77 kB acima do orçamento de 500 kB, sem desativar o aviso. Unitários: 28 arquivos (22 aprovados, seis reprovados), 104 casos (97 aprovados, sete falhas, zero ignorados), mais **cinco erros não tratados adicionais**.

O padrão anterior permanece: título de App (um), ActivatedRoute ausente em quatro componentes e mock sem buscarDetalhe em dois casos; os cinco erros adicionais são requisições a /api/usuarios/me. Fontes, testes e lockfile do frontend são idênticos à base. Não houve regressão de arquivos Angular, correção fora do escopo ou apresentação de build como testes aprovados. Integração visual da nova rota, auditoria e RNF17 permanece na Etapa 8; falhas dos testes e CI na 9.

### Migrações e limitações

Nenhuma migração nova necessária ou criada. Todos os testes antigos de V1–V6 e os novos ensaios com a rota específica passaram, inclusive validate após reiniciar e restaurar o backup mais recente em destino isolado. V1–V6 e fixtures SQL publicadas foram preservadas por hash. Nenhum banco real foi utilizado. H2 não certifica PostgreSQL/outro SGBD, privilégios ou concorrência do banco definitivo. O aviso Flyway sobre H2 mais novo permanece.

Guardar backup anterior e o mais recente após novas escritas, incluindo auditorias, Envers e histórico Flyway; restaurar em destino separado com binário compatível. Não existe garantia de downgrade semântico para versão que ignorava horário ou responsabilidade histórica. Não usar clean/repair/exclusão para simular retorno. Não foi executada CI remota nem declarada prontidão para produção.

Não há fila durável de reconciliação: código/tipo e identidade autorizada identificam a recusa, não um caso persistido resolvido. Datas locais históricas não foram convertidas para UTC ou regravadas. Outras apresentações de data fora dos fluxos tratados e integração global entre criação de grupos/agenda pertencem à revisão futura, não a uma alteração silenciosa desta etapa.

## Arquivos, preservação e publicação

SHA-256 confirmou **161 arquivos protegidos idênticos**, incluindo frontend/lockfile, CI, JWT/segurança, handler global, políticas de risco/visita/ocupação, migrações V1–V6 e fixtures SQL anteriores. Nenhum arquivo preexistente foi excluído. git diff --check passou. Inventário completo:

- [backend/buscaativa/src/main/java/com/pet/buscaativa/config/AgendaTempoProperties.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/config/AgendaTempoProperties.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/config/TimeConfig.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/config/TimeConfig.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/controllers/AgendamentoController.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/controllers/AgendamentoController.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/controllers/PendenciaFrequenciaHandler.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/controllers/PendenciaFrequenciaHandler.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/CorrigirFrequenciaDTO.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/CorrigirFrequenciaDTO.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/SessaoGrupoRepository.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/SessaoGrupoRepository.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/AgendamentoService.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/AgendamentoService.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/PoliticaTemporalFrequencia.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/PoliticaTemporalFrequencia.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/exceptions/PendenciaFrequenciaException.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/exceptions/PendenciaFrequenciaException.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/AgendamentoServiceImpl.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/AgendamentoServiceImpl.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/GrupoTerapeuticoServiceImpl.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/GrupoTerapeuticoServiceImpl.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/HistoricoPacienteServiceImpl.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/HistoricoPacienteServiceImpl.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/PacienteServiceImpl.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/PacienteServiceImpl.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/AgendaIntegritySupport.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/AgendaIntegritySupport.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/AttendanceTemporalHttpTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/AttendanceTemporalHttpTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/AttendanceTimePolicyTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/AttendanceTimePolicyTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/ClosedDayAgendaClock.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/ClosedDayAgendaClock.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/FrequencyCollisionIdentificationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/FrequencyCollisionIdentificationTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/FrequencyCorrectionConcurrencyTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/FrequencyCorrectionConcurrencyTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/FrequencyCorrectionTestSupport.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/FrequencyCorrectionTestSupport.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/GroupFrequencyTemporalTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/GroupFrequencyTemporalTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/HistoricalCorrectionOfferTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/HistoricalCorrectionOfferTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/SpecificCorrectionIndicatorTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/SpecificCorrectionIndicatorTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/SpecificCorrectionPersistenceTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/SpecificCorrectionPersistenceTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/SpecificFrequencyCorrectionTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/SpecificFrequencyCorrectionTests.java)
- [docs/etapa4-correcao-frequencia-retroativa.md](../docs/etapa4-correcao-frequencia-retroativa.md)
- [docs/plano-etapa0-fluxos-assistenciais.md](../docs/plano-etapa0-fluxos-assistenciais.md)

Commits de implementação:

- 3a9e4a1 — feat(agenda): valida horario assistencial por relogio oficial.
- 24fd20a — feat(frequencia): cria fluxo especifico de correcao.
- 31f29a0 — test(agenda): confirma independencia da oferta nas correcoes.
- 62fe99c — fix(frequencia): identifica colisoes sem perder integridade.
- 52ac2d2 — test(frequencia): cobre indicadores grupos e persistencia.
- 0423938 — fix(tempo): alinha indicadores ao relogio institucional.
- O commit documental de fechamento contém os resultados e o adendo do plano; seu SHA e a confirmação do push constam da resposta, evitando autorreferência.

Branch inicial/final: correcao-fluxos-assistenciais-sibraps. Base inicial: 1b0735d1dad5894737ec92ac691ecb915d339ae9. Antes da publicação, remoto desta branch ainda na base; main em 967d5162dd543ec9005608739128423903e75e30 e P03/P04/P05 em 05ee61a5123dfe4e914d7c2f5f486f7ecf310164. Somente push normal da branch autorizada, sem merge, PR, force push ou rebase. Evidências/XMLs/sumários/cópias sintéticas ficaram em /tmp/sibraps-etapa4, fora do commit. O plano foi atualizado exclusivamente para registrar os compromissos das Etapas 5–9, sem implementá-las.

## Respostas finais para revisão

1. Fluxo específico criado, compartilhando a aplicação clínica do legado.
2. Correção retroativa funciona, sem limite arbitrário de dias.
3. Correção no mesmo dia depois do horário funciona.
4. Antes e exatamente no horário de correção são recusados.
5. Fuso institucional America/Bahia explícito, tipado, injetável e testado independente do host.
6. Correções não dependem de capacidade atual.
7. Bloqueios posteriores não impedem isoladamente a correção.
8. Colisões reais detectáveis na operação continuam bloqueadas, sem mudar terceiros.
9. Código/tipo e atendimento autorizado identificam conflito para Etapa 6; nenhuma fila/resolução foi antecipada.
10. Permissões da Etapa 3 preservadas, inclusive USF específica e responsabilidade do atendimento.
11. Justificativa explicativa obrigatória nas correções, sem preenchimento automático.
12. Auditoria e Envers consistentes, inclusive rollback após INSERT real e restauração.
13. Indicadores recalculados pela cronologia completa existente, sem incrementos artificiais.
14. Gatilho de visita preservado e reavaliado corretamente.
15. P05 mantém original/sucessor, frequência e flag, inclusive em concorrência.
16. Responsabilidade histórica de grupos preservada; nova coordenadora não herda correções antigas.
17. Registro inicial autorizado da recepção preservado com a nova fronteira temporal inclusiva.
18. Os 579 casos anteriores passaram, sem ausências ou desativações.
19. Os 146 novos casos passaram; test e verify finais têm os mesmos 725 casos.
20. Nenhuma implementação fora do escopo; alinhamento final do relógio corrigiu somente indicadores dependentes da frequência.
21. APROVADA PARA REVISÃO, sem declaração de prontidão para produção.
22. Antes da Etapa 5, revisar esta entrega; permanecem regularização específica, reconciliação, HTTP global, interfaces, testes Angular/CI e SGBD definitivo, conforme Etapas 5–9. Etapa 5 não foi iniciada.

## Regressão adicional de identidade do evento clínico

Na conferência final foi reproduzido um caso não coberto anteriormente: agendamento individual de paciente com tipo de acompanhamento GRUPO_TERAPEUTICO pode representar a presença no histórico como PARTICIPACAO_GRUPO_TERAPEUTICO. A busca anterior da correção procurava somente PRESENCA/FALTA e criava outro evento numa alternância posterior. O teste red comprovou aumento indevido de um para dois eventos. A correção agora reconhece também essa categoria, sempre limitada ao mesmo agendamento, sem tocar eventos de sessões distintas ou mudar a classificação legada do fato.

O novo caso alterna FALTOU→PRESENTE→FALTOU→PRESENTE pela rota específica e comprova IDs e quantidade do histórico estáveis, três auditorias e ausência de contagem duplicada. Não há limpeza de duplicidades preexistentes: reconciliação continua na Etapa 6. Após esse ajuste, mvnw test completo passou com **726 casos, zero falhas/erros/ignorados**. Trata-se de correção do requisito de não duplicar fatos na Etapa 4, sem início da Etapa 5.
