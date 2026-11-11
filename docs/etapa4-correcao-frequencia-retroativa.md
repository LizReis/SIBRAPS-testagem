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
