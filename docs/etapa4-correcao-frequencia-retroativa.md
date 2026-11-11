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
