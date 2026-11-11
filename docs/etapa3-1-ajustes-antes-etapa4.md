# Etapa corretiva 3.1 — diagnóstico e validação antes da Etapa 4

## Objetivo e ponto de partida

Exclusivamente coerência risco/visita, responsabilidade histórica dos grupos e proteção do cancelamento genérico. Repositório LizReis/SIBRAPS-testagem; branch `correcao-fluxos-assistenciais-sibraps`; commit inicial `a2d1b98e8c848910cfaf63a65589b2f73e0d352b`, checkout limpo. Documentação das Etapas 0–3 e planejamento consultados antes das alterações. Baseline efetivamente executado com `bash ./mvnw verify`: **513 testes, zero falhas/erros/ignorados**, código 0. Evidências temporárias em `/tmp/sibraps-etapa3-1/baseline`.

## Diagnóstico anterior à implementação

**A:** o recálculo combina fatos individuais e frequências de sessões REALIZADAS, ordena por instante/origem/ID, reinicia a sequência a cada presença e deriva o gatilho de um par original/remarcação com ambas as faltas após a última presença. Duas faltas dão AMARELO e três VERMELHO; limites temporais existentes permanecem. Há uma exceção preexistente importante: INATIVO é classificado VERDE independentemente da sequência. Portanto não se deve afirmar que VERDE+gatilho seja impossível em todos os estados. Indicadores clínicos desconhecidos ficam provisórios e a lista defensiva mantém ativos vermelhos, com gatilho confirmado ou pendentes. Não há indicação inicial de defeito no cálculo; testes com comandos reais devem comprovar a conclusão, sem mudança gratuita da produção.

**B:** SessaoGrupo não guarda responsável histórico; AutorizacaoClinicaService e AuditoriaCorrecaoService usam o coordenador atual. Transferência posterior retira acesso de A e concede a B sobre sessões realizadas de A. A confirmação efetiva, sob locks já existentes (pacientes ordenados, grupo, sessão), é o ponto para consolidar o responsável validado. Planejado vínculo estável de sessão, separado do autor da correção, autorização histórica e DTO de leitura; sessões não realizadas acompanham o coordenador vigente. Migração aditiva posterior à V4 deve usar somente evidência Envers suficiente de transição para REALIZADA, nunca presumir o coordenador atual. Casos incompletos serão explicitamente pendentes e protegidos até reconciliação da Etapa 6. Auditorias antigas são imutáveis: seu snapshot, inclusive eventual coordenador registrado pela política anterior, não será reescrito nem tratado como prova infalível.

**C:** atualizarStatus permite CANCELADO para frequência PRESENTE/FALTOU, preservando a frequência canônica mas alterando a reserva. Há ainda uma guarda genérica que recusa qualquer comando em data futura, inclusive cancelamento sem frequência. A regra aprovada CA-01 exige exceção estritamente para cancelamento administrativo válido; as demais regras temporais não serão antecipadas. Planejado conflito 409 antes de idempotência/mutação para CANCELADO com frequência conhecida ou reserva SUBSTITUIDA. Mesmo serviço atende endpoint legado e chamada direta. P05 continua sendo substituição legítima, sem cancelamento genérico. Regularização de cancelamento indevido permanece na Etapa 5.

## Superfície afetada e preservação

Serviços: PacienteServiceImpl/PoliticaVisitaDomiciliar (verificação), AgendamentoServiceImpl, GrupoTerapeuticoServiceImpl, AutorizacaoClinicaService e AuditoriaCorrecaoService. Entidade SessaoGrupo e seus DTOs; repositórios/locks existentes de grupo, sessão, participante, paciente e usuário. Controllers existentes de grupos/agendamentos/auditoria permanecem entradas dos testes. Migrador offline H2 e novas migrações aditivas. Testes HTTP/JPA, migração em arquivo, Envers, concorrência e rollback. Não alterar handler global, JWT, H2, frontend, RF22, RNF17, limites de risco/visita ou V1–V4. Não iniciar Etapas 4–9, merge ou PR.

Resultados finais, arquivos e commits serão registrados após execução, sem declarar antecipadamente testes aprovados.

## Frente A — resultado comprovado

Onze novos testes passaram em `front-a-green`: dez de fluxo real HTTP/JPA e um de migração H2 antiga com frequência desconhecida. A-01 a A-09 cobertos, mais exceção INATIVO e limite temporal preexistente. A-02 resulta em duas faltas, AMARELO e visita; correção do original mantém SUBSTITUIDA e vínculo; correção do sucessor limpa o gatilho; outro par posterior conserva-o. Três faltas independentes geram VERMELHO sem visita. Confirmação efetiva de presença em grupo reinicia a cronologia. O relógio de teste é controlado somente para tornar a sessão de hoje determinística; nenhuma regra temporal de produção foi alterada.

A-08 executa a fixture SQL antiga existente e o migrador offline, inicia a aplicação com validate e prova revisão explícita, ausência de local de visita confirmada e recibo RNF17 com gatilho_confirmado=false. Frequências, versões e histórico assistencial não mudam. A-09 é o único teste de estado VERDE+gatilho artificial; a lista continua mostrando-o e o recálculo com evidência completa o corrige. A exceção INATIVO permanece VERDE+gatilho derivado, fora da lista operacional de ativos. **Nenhuma mudança na política de risco, visita ou seleção foi necessária.**

Em paciente ATIVO com histórico completo, o par válido tem duas faltas individuais posteriores à última presença relevante. Uma presença individual ou de grupo entre elas ou depois delas invalidaria esse par. Sem essa interrupção, a cronologia necessariamente contém pelo menos duas faltas consecutivas, atingindo AMARELO; tempo sem presença ou mais faltas só pode elevar o risco. Por isso VERDE+visita confirmada não foi produzido nesse conjunto de condições. A prova não se estende à exceção INATIVO nem a indicadores provisórios de histórico incompleto, e não justifica retirar a seleção defensiva.

## Reprodução dos defeitos antes das correções B/C

Em `diagnosis-ab`, A foi recusada com 403 após transferência (esperado: autorização e validação do motivo), e B corrigiu a sessão antiga com 200 (esperado: 403). Em `front-c-red`, cancelamento futuro sem frequência retornou 422 para administrador, responsável e recepção; seis casos de cancelamento de PRESENTE/FALTOU desses perfis retornaram 200 quando a nova regra exige 409. Foram falhas funcionais reproduzidas, não erros de lock aceitos como conflitos de negócio.

## Frente B — regra, modelo e migração implementados

Antes, a autorização e a nova auditoria usavam GrupoTerapeutico.coordenador. Agora SessaoGrupo possui responsavelAtendimento, responsabilidadePendente, motivoResponsabilidadePendente e origemResponsabilidade. Na confirmação efetiva, o coordenador validado sob lock de grupo é consolidado junto de REALIZADA. O cliente não escolhe o responsável; confirmação por administrador guarda o coordenador como responsável, e administrador continua sendo autor. Não existe setter público do vínculo, nem rota para substituir responsabilidade consolidada.

Transferências mantêm o responsável de sessões realizadas. A corrige as suas antigas se ainda pertencer ao escopo; B registra/corrige as novas de B. Sessões AGENDADAS, inclusive futuras e com marcação provisória, seguem o coordenador atual; frequência provisória não consolida responsabilidade. Inscrição retroativa também confere o responsável de cada sessão realizada: B não ganha um caminho alternativo para escrever fatos de A. Administração/receptivo futuros preservam suas regras operacionais existentes.

Correção e leitura da auditoria clínica conferem a identidade histórica depois de validar grupo inteiro, participantes e unidade, inclusive ID da USF. Administrador da unidade pode supervisionar sessões conhecidas mesmo se o antigo responsável mudou de unidade. Profissional que saiu da unidade não mantém acesso à unidade antiga. Recepção não recebe correção clínica. Sessão com dono desconhecido bloqueia mutação com 409, inclusive por administrador até procedimento da Etapa 6; administrador local pode consultar a auditoria existente para análise, profissional não pode usar a pendência como autorização.

V5 adiciona colunas e FK; V6 reconstrói conservadoramente, sem atualizar versões, participantes, frequências, auditorias, históricos ou revisões antigas. Exige criação Envers AGENDADA, transição MOD de AGENDADA para REALIZADA, grupo constante, ausência de apagamento/reabertura contraditória e histórico de criação/coordenação do grupo até a revisão da realização. Usa o coordenador dessa revisão, nunca o atual, criador do cadastro ou autor de evento clínico. Primeiro snapshot já REALIZADA, história incompleta/contraditória ou vínculo inexistente resultam em pendência explícita. A checagem de integridade exige responsável conhecido ou pendência para REALIZADA. Inicialização valida, não faz backfill persistente automático. V1–V4 e fixture antiga permanecem intocadas.

A fixture `group-responsibility-v4.sql` foi gerada usando o jar do baseline a2d1b98, classes antigas primeiro no classpath, cópia H2 antiga migrada até V4 e APIs reais de confirmação, correção, transferência e nova correção. Sua sessão 1 possui Envers da realização por A e dois registros imutáveis com snapshots A, depois B, reproduzindo a política anterior. A migração recupera A e conserva ambos os snapshots. O DTO de auditoria mantém responsavelAtendimentoId como snapshot original e acrescenta responsavelHistoricoSessaoId e responsabilidadeSessaoPendente; não reescreve o passado para esconder o defeito. Histórico de grupo continua registrando o usuário executor conforme contrato anterior e não é utilizado para inferir dono clínico.

Casos legados pendentes de responsabilidade não tornam frequências conhecidas desconhecidas: o recálculo clínico continua usando os fatos registrados. Indicadores provisórios por falta de evidência de frequência têm tratamento independente, já validado na Frente A.

## Frente C — correção e compatibilidade

O mesmo serviço central atende o PATCH legado e as duas sobrecargas diretas. Depois de escopo/autorização, versão e pendência histórica, CANCELADO com PRESENTE/FALTOU ou reserva SUBSTITUIDA gera 409 antes de idempotência, justificativa ou mutação. Motivo informado não autoriza cancelamento genérico. Uma reserva sem frequência pode ser cancelada em data passada/hoje/futura por perfil já autorizado; a guarda futura ganhou somente a exceção CANCELADO. Registro clínico futuro e demais transições temporais mantêm a regra anterior.

P05 continua substituindo o original, conserva FALTOU, agendamentoOriginal, remarcacaoAposFalta, identidade do paciente e controle de vagas. Não usa o novo bloqueio de cancelamento como bloqueio indiscriminado de remarcação. Cancelamentos incorretos anteriores continuam preservados, sem nova rota de regularização; Etapa 5 permanece pendente.

## Expectativas anteriores adaptadas

Nenhum teste removido ou desabilitado. Contagens esperadas de migração evoluem de 4 para 6 (ou 6 menos versão inicial). Fixtures sintéticas de autorização/auditoria/justificativa que representam responsável conhecido agora o consolidam explicitamente, sem atribuir dono aos legados ambíguos. A fixture SQL de constraint fornece responsável conhecido para que continue testando a constraint de auditoria original. Os novos exemplos gerados pelo perfil dev também consolidam a responsabilidade conhecida na criação fictícia; nenhum banco persistente é reatribuído por essa mudança.

O teste canônico de cancelamento clínico anterior cria diretamente uma situação legada para comprovar preservação/leitura e ausência de regularização; ele continua cobrindo isso e não foi relaxado. As novas expectativas HTTP intencionais são: A autorizada/B negada sobre sessão antiga de A; cancelamento de frequência conhecida 409; cancelamento futuro sem frequência autorizado 200. Handler global não foi alterado.

## Validação integrada, permissões, concorrência e rollback

As execuções finais foram feitas no Java 21.0.13, Maven Wrapper e H2 2.4.240. Comandos reais, a partir de backend/buscaativa, usando JAVA_HOME e settings/cache do ambiente e **sem skipTests**:

```bash
bash ./mvnw -s /workspace/.cache/sibraps/maven-settings.xml -B -Dmaven.repo.local=/workspace/.cache/sibraps/m2/repository test
bash ./mvnw -s /workspace/.cache/sibraps/maven-settings.xml -B -Dmaven.repo.local=/workspace/.cache/sibraps/m2/repository verify
```

O test final usou ambiente herdado normal; verify também comprovou isolamento sob SPRING_PROFILES_ACTIVE=dev e credenciais sintéticas fortes no processo. Testes de segurança continuam usando o perfil de teste isolado. Mockito precisou anexar seu agente Java fora da restrição do sandbox; isso não desativou segurança, testes ou validações.

| Conjunto | Executados | Aprovados | Reprovados | Ignorados |
|---|---:|---:|---:|---:|
| Baseline inicial verify | 513 | 513 | 0 | 0 |
| Casos anteriores retidos na entrega | 513 | 513 | 0 | 0 |
| Novos A: fluxo real 10 + migração incompleta 1 | 11 | 11 | 0 | 0 |
| Novos B: responsabilidade/permissões/concorrência 23 + migração 8 | 31 | 31 | 0 | 0 |
| Novos C: cancelamento, P05, serviço e rollback | 24 | 24 | 0 | 0 |
| mvnw test final | 579 | 579 | 0 | 0 |
| mvnw verify final | 579 | 579 | 0 | 0 |
| Angular unitários | 104 | 97 | 7 | 0 |

As linhas de conjuntos são recortes, não devem ser somadas às duas execuções completas. Comparação por classe e nome de cada caso dos XMLs Surefire: **513 casos antigos presentes, 66 novos, nenhum ausente**. Os mesmos 579 casos constam de test e verify. Nenhuma exclusão ou desativação.

Os 23 casos novos de grupo cobrem GR-01 a GR-12, incluindo motivos ausentes, antiga/nova coordenadora, administrador local/externo, USFs distintas, recepção, responsabilidade pendente, auditorias antigas, inscrição retroativa sem bypass, sessão futura, marcação provisória, tentativa de escolher responsável pelo JSON e responsável antigo transferido de unidade. Os 24 casos de cancelamento cobrem CA-01 a CA-16 e verificam snapshots de todas as tabelas nos bloqueios, incluindo frequência, reserva, versão, indicadores, histórico, auditoria e Envers. Registro clínico futuro continua recusado.

Concorrência final (os números de sucesso variam conforme a serialização válida):

| Execução | Disputas | Operações | Confirmadas | Rejeitadas |
|---|---:|---:|---:|---:|
| test | 53 | 126 | 62 | 64 |
| verify | 53 | 126 | 64 | 62 |

São 47 disputas anteriores e seis novas GR-11, cada operação em transação/conexão independente. Preservados correção × criação, correção × remarcação, versão, dois corretores, unidades e locks ordenados. Transferência × correção da antiga responsável permite ambas; transferência × confirmação por A confirma com A se vier antes da transferência ou recusa A com 403 se vier depois; confirmação pelo administrador congela A ou B conforme a coordenação sob o lock naquele instante, conferida pela revisão Envers. Nenhum deadlock inesperado/erro técnico foi aceito como resultado funcional. Não há synchronized substituindo lock de banco.

Rollback novo de confirmação comprova o responsável já gravado na transação antes da falha e compara todas as tabelas depois. Rollback de cancelamento força flush e comprova CANCELADA dentro da transação antes de falhar, depois restaura integralmente o snapshot. Permaneceram aprovados os testes antigos de falha após INSERT real de auditoria, falha no histórico, segundo participante do lote, falha de evento RNF17 e falha de auditoria da lista. Não existem correções/auditorias parciais confirmadas.

## Migração, reinicialização e restauração

Os oito novos testes de migração de grupo e os 17 anteriores do núcleo AgendaLegacyMigrationTests/ClinicalAuditMigrationTests passaram; A-08 acrescenta a frequência migrada desconhecida. Entradas antigas, V2/V3/V4, versão atual, FK, checks, idempotência, revisões antigas e cópia restaurada foram exercitadas. Seis variantes incompletas/contraditórias da fixture isolada ficaram pendentes sem responsável presumido. A leitura pelo AuditReader de revisão anterior à V5 também foi executada: sessão REALIZADA e coordenador A continuam legíveis; novos campos daquela revisão permanecem ausentes, sem reescrever Envers.

O ciclo de aplicação sobre a fixture V4 migra, recusa B, autoriza correção de A, mantém os dois registros imutáveis anteriores, acrescenta uma auditoria legítima, fecha/reabre e restaura o backup mais recente em outro destino. Três inicializações com validate passaram; snapshots integrais após reabrir/restaurar são iguais; responsável A, versão 4 e três auditorias preservados. Sessão ambígua continua protegida, e seu administrador só consulta para análise.

O jar empacotado pelo verify foi executado de fato com PropertiesLauncher em outras duas cópias descartáveis:

| Entrada CLI | Primeira execução | Repetição | Conferência |
|---|---:|---:|---|
| SQL antigo V0 | 6 migrações | 0 | 12 atendimentos, quatro pendentes clínicos, três ocupações, nenhuma auditoria clínica/RNF17 inventada |
| Fixture real até V4 | 2 migrações | 0 | dono A, sessão ambígua pendente, sessão AGENDADA sem dono consolidado; duas auditorias antigas e versão 3 preservadas |

Todos os comandos de preparação, migração, repetição e consulta SQL retornaram 0. As duas tabelas de auditoria continuam com quatro operações UPDATE/DELETE protegidas por triggers. Não houve execução em banco real.

Execução offline, somente em cópia H2 isolada, com escritores suspensos e DB_URL/DB_USERNAME/DB_PASSWORD fornecidos no ambiente:

```bash
java -Dloader.main=com.pet.buscaativa.migration.AgendaMigrationTool \
  -cp backend/buscaativa/target/buscaativa-0.0.1-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher
```

Exige H2 em arquivo e DB_CLOSE_DELAY=0, schema reconhecido, origem Flyway válida e proteções finais. Guardar backup anterior, inventário e backup mais recente com novas escritas, auditorias, revisões e histórico Flyway; testar restauração em destino separado com binário compatível. Não retornar apenas ao backup inicial depois de novas escritas. Não foi garantido downgrade semântico para o binário antigo. H2 pode confirmar DDL separadamente: falha parcial exige diagnóstico e recuperação pela cópia verificada, sem clean/repair automático, exclusões ou migração destrutiva. O aviso de versão H2 mais nova que a oficialmente verificada pelo Flyway permanece visível. Resultado H2 não certifica outro SGBD.

## Angular e limites conhecidos

Node 24.19.0, npm 11.6.2 selecionado por npx conforme packageManager. Foram executados npm ci, npm run build e npm test -- --watch=false: códigos **0, 0 e 1**. Build: 847,77 kB, aviso preservado de 347,77 kB acima do orçamento de 500 kB. Unitários: 28 arquivos (22 aprovados, seis reprovados), 104 casos (97 aprovados, sete falhas) e **cinco erros não tratados adicionais**, não contados como novos testes.

Reproduzido o padrão anterior: teste antigo de título de App (um), ActivatedRoute ausente em MainLayout/DetalheProfissional/Inicio/Sidebar (quatro), mock sem buscarDetalhe em DetalhePaciente (dois); cinco erros HTTP de /api/usuarios/me. Nenhum arquivo de frontend/lockfile foi alterado para ocultá-los. Correção reservada à Etapa 9, interfaces à 8. CI remota e banco definitivo não foram certificados; não há declaração de prontidão para produção.

## Regras preservadas, mudanças intencionais e pendências

Preservados P01 JWT/P02 H2; P03 grade, turnos, disponibilidade, capacidade e bloqueio; P04 locks/versões/colisões; P05 identidade e cadeia de remarcação; correção factual histórica independente da oferta atual; reserva e frequência separadas; autorização individual, justificativas, auditorias imutáveis, RF22, RNF17, contagem de faltas, risco, visita, histórico, Envers e isolamento por unidade/USF. Os 513 casos anteriores continuam presentes/aprovados.

Mudanças intencionais limitadas: autorização de sessões realizadas usa seu responsável histórico; novas auditorias guardam esse responsável; cancelamento genérico de frequência conhecida/substituição conflita; cancelamento futuro válido sem frequência funciona. As expectativas adaptadas e a evidência anterior estão descritas acima. Risco/visita/limites/seleção defensiva e handler global não mudaram.

Não foi necessária decisão nova para concluir as três frentes: o pedido já aprovou as regras. Dependem dos fluxos futuros a política temporal definitiva (4), regularização específica de cancelamentos (5), reconciliação de responsabilidade/frequência ambíguas (6), HTTP global (7), interfaces (8) e testes Angular/CI/SGBD definitivo (9). O planejamento foi atualizado sem antecipar implementação.

## Arquivos, integridade e commits

Conferência SHA-256: **158 arquivos protegidos idênticos** à base, incluindo frontend/lockfile, CI, segurança, handler global, políticas de risco/visita/ocupação, V1–V4 e fixture SQL antiga. Nenhum arquivo preexistente foi excluído. git diff --check passou. Inventário completo das 26 alterações:

- [backend/buscaativa/src/main/java/com/pet/buscaativa/config/DevConfig.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/config/DevConfig.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/SessaoGrupo.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/SessaoGrupo.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/AuditoriaCorrecaoDTO.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/AuditoriaCorrecaoDTO.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/SessaoGrupoDTO.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/SessaoGrupoDTO.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/migration/AgendaMigrationTool.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/migration/AgendaMigrationTool.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/migration/ResponsabilidadeSessaoGuard.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/migration/ResponsabilidadeSessaoGuard.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/migration/V6__identificar_responsabilidade_historica.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/migration/V6__identificar_responsabilidade_historica.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/AuditoriaCorrecaoService.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/AuditoriaCorrecaoService.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/AutorizacaoClinicaService.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/AutorizacaoClinicaService.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/AgendamentoServiceImpl.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/AgendamentoServiceImpl.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/GrupoTerapeuticoServiceImpl.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/GrupoTerapeuticoServiceImpl.java)
- [backend/buscaativa/src/main/resources/agenda-migration/h2/V5__responsabilidade_historica_sessao.sql](../backend/buscaativa/src/main/resources/agenda-migration/h2/V5__responsabilidade_historica_sessao.sql)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/AgendaLegacyMigrationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/AgendaLegacyMigrationTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/ClinicalAuditIntegrationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/ClinicalAuditIntegrationTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/ClinicalAuditMigrationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/ClinicalAuditMigrationTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/ClinicalAuthorizationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/ClinicalAuthorizationTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/ClinicalJustificationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/ClinicalJustificationTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/CorrectiveMigrationApplication.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/CorrectiveMigrationApplication.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/GroupHistoricalResponsibilityTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/GroupHistoricalResponsibilityTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/GroupResponsibilityMigrationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/GroupResponsibilityMigrationTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/RecordedFrequencyCancellationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/RecordedFrequencyCancellationTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/RiskVisitMigratedHistoryTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/RiskVisitMigratedHistoryTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/RiskVisitRealFlowTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/RiskVisitRealFlowTests.java)
- [backend/buscaativa/src/test/resources/fixtures/group-responsibility-v4.sql](../backend/buscaativa/src/test/resources/fixtures/group-responsibility-v4.sql)
- [docs/etapa3-1-ajustes-antes-etapa4.md](../docs/etapa3-1-ajustes-antes-etapa4.md)
- [docs/plano-etapa0-fluxos-assistenciais.md](../docs/plano-etapa0-fluxos-assistenciais.md)

Commits produzidos:

- 808ca27 — test(assistencial): valida coerencia entre risco e gatilho de visita.
- 7d5bb99 — fix(grupos): preserva responsabilidade historica das sessoes.
- 88e7412 — fix(agenda): bloqueia cancelamento de frequencia registrada.
- O commit documental de fechamento contém este relatório e o adendo do plano; seu SHA final e o push serão informados na resposta para evitar autorreferência.

Branch única correcao-fluxos-assistenciais-sibraps. Antes de publicar, remoto desta branch ainda em a2d1b98; main em 967d5162dd543ec9005608739128423903e75e30 e P03/P04/P05 em 05ee61a5123dfe4e914d7c2f5f486f7ecf310164. Publicação somente por push normal, sem merge, PR, force push, rebase ou exclusão. Logs, XMLs, sumários, manifestos e cópias de ensaio ficaram em /tmp/sibraps-etapa3-1, fora do commit. A fixture V4 sintética, necessária à regressão reproduzível, é a única cópia de dados nova incorporada.

## Respostas finais para revisão

1. Coerência risco/gatilho confirmada para ativos completos; exceção INATIVO e pendências explicitadas.
2. Defeitos reais B/C reproduzidos; nenhum defeito de cálculo A exigiu alteração de produção.
3. Todos os cenários reais novos passaram.
4. Seleção defensiva mantida.
5. Responsabilidade histórica de sessões realizadas preservada.
6. Nova coordenadora atua nas suas sessões e nas não realizadas sob coordenação vigente.
7. Impedida de corrigir sessões antigas de outra responsável.
8. Coordenadora anterior corrige suas sessões se ainda autorizada no escopo.
9. Administrador da unidade supervisiona sessões conhecidas; pendência exige reconciliação futura.
10. Auditorias anteriores preservadas integralmente, inclusive snapshots da política antiga claramente distinguidos.
11. Cancelamentos genéricos de fatos clínicos bloqueados.
12. Cancelamentos administrativos válidos sem frequência continuam funcionando, inclusive futuros.
13. P05 preservado e testado.
14. Todos os 513 testes anteriores passaram, sem casos ausentes.
15. Todos os 66 novos testes passaram nas duas execuções finais.
16. Migrações, reabertura, leitura Envers, restauração e CLI empacotada testadas em cópias isoladas H2.
17. Nenhuma implementação fora das três frentes; somente planejamento futuro adicional solicitado.
18. APROVADA PARA REVISÃO, com as limitações Angular/H2/futuras explicitadas; não equivale a produção.
19. Permanecem as Etapas 4–9, sem antecipação: política temporal, regularização, reconciliação, HTTP, interfaces, Angular/CI/banco definitivo.
