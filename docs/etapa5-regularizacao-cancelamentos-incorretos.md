# Etapa 5 — regularização de cancelamentos incorretos

## Diagnóstico preservado antes da implementação

Repositório LizReis/SIBRAPS-testagem, branch correcao-fluxos-assistenciais-sibraps, HEAD inicial 5415eb837dbc3db0f1c102cbf20082a735f773bc, checkout limpo. Relatórios das Etapas 0–4 e RN-05/RN-06/RN-10 consultados. Baseline executado: **mvnw verify, 726 testes, zero falhas/erros/ignorados**. Nenhuma execução em banco real.

O PATCH legado e as sobrecargas de atualizarStatus ainda permitem CANCELADA+NAO_REGISTRADA→AGENDADA/REMARCADA no próprio dia, sem motivo/auditoria específica, respeitando apenas oferta e a guarda de data (não horário). Datas futuras são recusadas por guarda genérica antiga. CANCELADA→frequência é recusada; a infraestrutura de correção não admite NR→P/F e não será enfraquecida para isso. setSituacaoAtendimento preserva a reserva canônica CANCELADA e mantém a projeção CANCELADO, mas não é uma operação de domínio explícita de regularização. Histórico genérico usa a projeção e não geraria o fato clínico correto quando CANCELADA continuar CANCELADA. Indicadores/repositórios clínicos já consultam frequência canônica, inclusive cancelamentos e substituições; ocupação canônica CANCELADA+P/F é false.

| Operação | Origem | Destino | Horário | Oferta | Auditoria |
|---|---|---|---|---|---|
| Recuperar reserva | CANCELADA/NR | AGENDADA/NR se raiz; REMARCADA/NR se sucessora íntegra | Estritamente futuro | Obrigatória | Nova regularização RECUPERACAO_RESERVA |
| Registrar fato | CANCELADA/NR | CANCELADA/P ou F | Estritamente após horário | Não condiciona fato; colisão efetiva bloqueia | Nova regularização REGISTRO_FATO_ASSISTENCIAL |
| Correção posterior | CANCELADA/P ou F com origem confiável | CANCELADA/F ou P | Política estrita Etapa 4 | Sem oferta; colisão protegida | Auditoria clínica existente P↔F |
| Legado genérico | CANCELADA | Recuperação ou primeiro fato | Qualquer | — | Recusado; operação específica obrigatória |

Exatamente no horário: não recuperar como futura e não regularizar retrospectivamente. Não há prazo máximo retrospectivo. Dados ausentes/ambíguos continuam pendentes. Recepção não regulariza; preserva cancelamento administrativo e registro inicial comum já autorizados.

Planejado: dois DTOs e operações específicas; locks paciente→profissional→atendimento; cadeia P05 validada sob lock do paciente; estado derivado do vínculo persistido, sem escolher reserva no payload; motivos/versão obrigatórios; auditoria insert-only própria com V7 aditiva, sem backfill. Histórico factual explícito usa data/hora assistencial e responsável, sem inventar evidência. Evento clínico já existente/contraditório exige reconciliação, não escolha automática. Correção posterior somente pela rota específica da Etapa 4 com origem auditada e sequência de versões/frequências compatível; CANCELADA+P/F legado não se torna confiável por presunção.

Colisões estruturadas reutilizam a Etapa 4; histórico incompatível e origem inconsistente também produzem pendência segura. Nenhuma fila/resolução da Etapa 6, interface da 8 ou reescrita global de HTTP da 7 será antecipada. V1–V6, constraints clínicas, risco/visita, RF22/RNF17, JWT/H2 e frontend permanecem protegidos.

Reprodução efetiva anterior à correção: cinco casos falharam em legacy-red — quatro requisições AGENDADO/REMARCADO, com e sem motivo, e uma chamada às sobrecargas diretas. O serviço antigo confirmou a recuperação indevida em vez de recusá-la. Foram falhas funcionais, não falhas técnicas de lock.

## Fundação auditável e primeiras operações

V7 é aditiva: nova tabela de regularizações, FKs, índice de consulta, unicidade por atendimento/versão anterior, CHECK semântico e trigger append-only. FK composta exige que paciente e responsável do registro pertençam à identidade do agendamento. Nenhuma auditoria clínica P↔F foi afrouxada; não há backfill de cancelamentos supostamente incorretos. O schema guard valida a nova proteção; somente H2 em memória descartável create/create-drop recebe instalação automática equivalente para testes/dev. Arquivos persistidos exigem migração explícita.

Cada fluxo possui método de domínio próprio e serviço transacional autorizado. A recuperação deriva AGENDADA/REMARCADA do vínculo original íntegro, não do payload. O registro factual mantém CANCELADA e CANCELADO, com ocupaVaga=false, e cria um único evento clínico datado do atendimento, além da auditoria própria. Justificativa é a declaração explicativa do profissional; não é inventada referência documental ou comprovação externa.

Origem confiável da correção posterior exige uma única auditoria factual correspondente a paciente/responsável/unidade, sequência contígua de versões e frequências nas correções e um evento clínico único compatível com o estado atual. Histórico existente na primeira regularização, origem contraditória, cadeia bifurcada/cíclica ou identidade incompatível bloqueiam a operação para reconciliação.

Primeira rodada focada completa: 90 testes de recuperação, fato, segurança e rollback passaram. As 726 regressões anteriores passaram na primeira rodada integrada; o único caso novo então corrigido era um fixture de capacidade com turno incorreto, sem mudança da regra de capacidade.


## 1. Objetivo

Implementar RN-05/RN-06 por duas operações explícitas no atendimento existente. Recuperar uma reserva futura volta a adquirir vaga; registrar o resultado assistencial passado preserva a evidência administrativa do cancelamento. Etapas 6–9 não foram antecipadas.

## 2. Git inicial

Branch inicial/final: `correcao-fluxos-assistenciais-sibraps`. Commit inicial completo: `5415eb837dbc3db0f1c102cbf20082a735f773bc`, checkout limpo e ancestralidade confirmada. Baseline: 726 casos aprovados em `mvnw verify`, zero ignorados. Sem banco real, merge, rebase, force push ou PR. A publicação é um push normal somente nesta branch.

## 3. Diagnóstico do fluxo antigo

Foram reproduzidos cinco bypasses antes da correção: recuperação genérica no próprio dia, com/sem motivo, para AGENDADO/REMARCADO, e sobrecargas diretas. O antigo caminho não exigia auditoria específica. Datas futuras eram barradas pela guarda genérica; a permissão inconsistente no mesmo dia não deve ser conservada. Os cinco testes falharam funcionalmente antes da implementação e passaram depois. CANCELADA→primeira frequência continuava bloqueada, sem operação legítima alternativa.

## 4. Matriz canônica antes/depois

| Origem | Comando autorizado | Resultado canônico | Projeção legada | Ocupa vaga |
|---|---|---|---|---|
| CANCELADA + NR | Recuperar raiz futura com oferta | AGENDADA + NR | AGENDADO | true |
| CANCELADA + NR | Recuperar sucessora P05 íntegra | REMARCADA + NR | REMARCADO | true |
| CANCELADA + NR | Registrar presença passada comprovada | CANCELADA + PRESENTE | CANCELADO | false |
| CANCELADA + NR | Registrar falta passada comprovada | CANCELADA + FALTOU | CANCELADO | false |
| CANCELADA + P/F com origem auditada | Correção clínica específica P↔F | CANCELADA + F/P | CANCELADO | false |
| CANCELADA + P/F sem origem confiável | Tentativa de correção | Pendente, sem alteração | Preservada | Preservado |

NR significa NAO_REGISTRADA. SUBSTITUIDA nunca é recuperada como reserva independente. Nenhum estado desconhecido é convertido automaticamente em FALTOU.

## 5. Duas operações distintas

A operação de reserva usa dados persistidos e disponibilidade atual, sem evento clínico nem recálculo de frequência. A operação factual aceita somente P/F explicitamente declarados e justificados pelo responsável/admin local; grava histórico clínico e recalcula indicadores sem adquirir vaga. A justificativa não é um laudo automaticamente validado nem uma referência documental inventada pelo sistema.

## 6. Política temporal

`Clock` institucional e configuração `America/Bahia` da Etapa 4. Recuperação: previsto > agora. Regularização factual: previsto < agora. Exatamente no horário, nenhum dos dois fluxos é aceito. Registro inicial comum mantém a fronteira inclusiva preexistente. Datas/horários ausentes, sobreposições ou lacunas de fuso ficam pendentes; não se usa relógio do navegador nem prazo retrospectivo arbitrário. Foram testados antes/exatamente/depois e fato de oito meses antes.

## 7. Recuperação da reserva futura

Exige versão atual, CANCELADA+NR identificada, motivo válido, nenhum fato anterior, cadeia P05 íntegra, ausência de histórico clínico contraditório, grade/turno/horário, disponibilidade, capacidade, bloqueios e colisões paciente/profissional/grupo. Mesmo ID, paciente, profissional, data, hora, original e flag de remarcação são preservados. A sucessora legítima retorna REMARCADA. Falta de oferta ou vaga ocupada não produz escrita parcial, cancelamento de terceiro ou aumento de capacidade.

## 8. Frequência com reserva cancelada

Exige CANCELADA+NR passada, identidade conhecida, dados temporais válidos, ausência de migração pendente, cadeia e histórico coerentes e ausência de colisão real. Atualiza somente o fato no mesmo atendimento, mantém CANCELADA e ocupaVaga=false. Testes confirmam independência de grade alterada, ausência de oferta, capacidade zero/esgotada e bloqueio posterior. A requisição deve informar PRESENTE ou FALTOU; não há inferência de ausência por cancelamento.

## 9. Projeção legada

A API responde `situacaoAtendimento=CANCELADO` após o registro factual e expõe `situacaoReserva`/`frequenciaAssistencial` canônicas. Consultas clínicas existentes já usam a frequência canônica; não foi feita troca global da projeção. A UI antiga não ganha um atalho inseguro para manter uma ação genérica funcionando. A futura interface precisa apresentar ambos os estados.

## 10. Ocupação de vaga

A política canônica de ocupação não foi alterada: CANCELADA+P/F ocupaVaga=false. Verificar colisão clínica não transforma fato histórico em reserva operacional. A checagem compartilhada também identifica PRESENTE em registros cancelados/substituídos, evitando criar outra reserva incompatível depois do fato, mesmo quando a contagem operacional é zero.

## 11. Escopo e responsabilidade

| Executor | Recuperar / registrar fato / consultar auditoria |
|---|---|
| Responsável do atendimento na unidade | Permitido |
| Administrador da mesma unidade | Permitido; executor distinto do responsável |
| Outro profissional local | 403 |
| Recepção | 403 |
| Administrador externo / outra USF | 404 sem exposição |
| Sem autenticação | 401 |

Serviços aplicam a mesma autorização independentemente do controller. A USF é identificada individualmente. Identidade, perfil, unidade, responsável e horário enviados como campos extras no JSON são ignorados: os valores utilizados vêm do banco e da autenticação. Os testes inspecionam o autor efetivo persistido.

## 12. Justificativa

Obrigatória, normalizada pela política existente da Etapa 3, 10–2000 caracteres úteis e sem motivos genéricos recusados pela política. Ausente, vazia, curta, longa ou genérica: 422 e snapshot integral preservado. Versão obrigatória inteira não negativa. Não se fabrica justificativa histórica durante migração.

## 13. Contratos HTTP

Todos exigem bearer token. Exemplos de payload sintético, sem autoria ou alteração de agenda:

```http
POST /api/agendamentos/{id}/regularizacao-cancelamento/recuperar-reserva
Content-Type: application/json

{"version":3,"justificativa":"Cancelamento operacional conferido pelo responsável; reserva futura preservada."}
```

```http
POST /api/agendamentos/{id}/regularizacao-cancelamento/frequencia
Content-Type: application/json

{"novaFrequencia":"PRESENTE","version":3,"justificativa":"Comparecimento conferido no prontuário pelo responsável do atendimento."}
```

Ambos retornam 200 com `AgendamentoDTO` existente, preservando identidade e incrementando a versão uma vez. Recorte ilustrativo da resposta factual:

```json
{"id":123,"situacaoAtendimento":"CANCELADO","situacaoReserva":"CANCELADA","frequenciaAssistencial":"PRESENTE","ocupaVaga":false,"version":4}
```

O DTO completo inclui paciente/profissional públicos, data, turno, hora e vínculo original. O exemplo é um contrato, não um registro real de paciente.

`GET /api/agendamentos/{id}/regularizacoes-cancelamento?page=0&size=20` retorna página de `AuditoriaRegularizacaoDTO`, ordenada por instante/versão/ID decrescentes. Página >=0, tamanho 1–100. Campos: id, agendamentoId, pacienteId público, usuarioId do executor, perfil/unidade, responsavelAtendimentoId público, tipoRegularizacao, reserva/frequência anteriores/resultantes, justificativa, ocorridoEm UTC e versões.

| Resposta | Interpretação |
|---|---|
| 401 / 403 / 404 | Autenticação, permissão ou recurso fora do escopo |
| 409, erro `Conflito de atualização.` | Versão obsoleta explícita; o handler global também possui caminho técnico preexistente, que não foi reclassificado |
| 409, `Conflito de regra de negócio.` | Estado incompatível, falta de oferta, capacidade/bloqueio, repetição inelegível ou pendência |
| 422 | Motivo/versão ausente ou inválida, grade inválida ou tentativa factual antes/no horário |
| 400 | JSON/enum não desserializável segundo o padrão existente |

Pendências retornam `codigo`, `tipoConflito`, `agendamentoId` da tentativa, `sessaoId` quando aplicável, timestamp/path e mensagem segura, sem identificar outro paciente. Códigos: `CONFLITO_ASSISTENCIAL_PENDENTE_RECONCILIACAO`, `HORARIO_ASSISTENCIAL_PENDENTE_RECONCILIACAO`, `HISTORICO_ASSISTENCIAL_PENDENTE_RECONCILIACAO`, `ORIGEM_REGULARIZACAO_PENDENTE_RECONCILIACAO` e `CADEIA_REMARCACAO_PENDENTE_RECONCILIACAO`. Não foi prometido código novo uniforme para todos os erros antigos.

## 14. Proteção do legado e correção posterior

PATCH genérico e sobrecargas diretas não recuperam CANCELADA nem lançam/corrigem frequência nela, mesmo com motivo. Cancelamento administrativo válido sem frequência e registro inicial comum pela recepção mantêm as regras anteriores.

P↔F posterior de fato regularizado usa exclusivamente `PATCH /api/agendamentos/{id}/frequencia/correcao`. A origem exige uma única regularização factual, identidade/unidade compatíveis, sequência contígua de versões/frequências nas correções e evento clínico único compatível. CANCELADA+P/F arbitrária/legada, lacunas inexplicadas de versão ou histórico contraditório continuam pendentes; não se certifica retroativamente sua origem.

## 15. Auditoria própria

`tb_auditoria_regularizacao_cancelamento`: UUID, atendimento/paciente, executor e responsável distintos, perfil/unidade, tipo RECUPERACAO_RESERVA ou REGISTRO_FATO_ASSISTENCIAL, reserva/frequência antes/depois, motivo, instante UTC com microssegundos e versões antes/depois. Entidade imutável, repositório somente leitura, inserção interna com autorização e transação MANDATORY. Sem REQUIRES_NEW.

FKs diretas e FK composta vinculam paciente/responsável à identidade do atendimento; unicidade atendimento/versão anterior, CHECK de semântica e versão+1, índice de consulta e trigger que recusa UPDATE/DELETE. A tabela clínica P↔F anterior não aceita NR→P/F e suas constraints não foram afrouxadas. Auditorias de busca ativa e de correção permanecem íntegras.

## 16. Histórico e Envers

A primeira regularização factual cria exatamente um evento PRESENCA/FALTA com atendimento original, paciente e responsável, datado da data/hora assistencial persistida. A recuperação não cria evento clínico. Correções posteriores atualizam o mesmo evento e acrescentam auditoria P↔F, sem duplicação de fato. Instante UTC da operação permanece na auditoria, separado do instante assistencial local.

Histórico clínico prévio único, duplicado ou contraditório impede a primeira regularização em vez de escolher automaticamente uma versão. Revisões Envers existentes foram preservadas nos snapshots de migração/backup; transações de atualização continuam produzindo revisões. A nova trilha imutável não substitui Envers. O ciclo persistido verifica explicitamente três revisões Envers do atendimento criado/cancelado/recuperado e três do atendimento cancelado/regularizado/corrigido.

## 17. Colisões

Consultas preservam proteção de reserva ativa e acrescentam presença canônica histórica, com exclusão do próprio ID. São verificadas colisões por paciente e por profissional, inclusive com grupos e responsável histórico. Colisão efetiva impede o primeiro registro tanto de P como de F: não se usa FALTOU como saída automática para um conflito. A resposta apenas identifica pendência; não reconcilia, transfere responsabilidade ou cancela terceiros.

## 18. Concorrência e defeito adicional reproduzido

Ordem dos locks individuais: paciente → profissional → atendimento. A identidade inicial é projeção escalar; a entidade completa é lida sob lock. P05 é validado sob lock do paciente. As requisições concorrentes usam conexões/transações independentes; IDs de sessão H2 são conferidos.

A corrida recuperação × capacidade zero revelou um defeito real: ambas confirmavam porque a escrita de oferta não compartilhava o lock do profissional. Correção necessária ao escopo: saves de disponibilidade semanal, exceção e bloqueio agora são transacionais e usam esse mesmo lock. Exclusões também o usam, atualizam o recurso sob lock e recusam troca concorrente de proprietário. Não se mudou o cálculo de capacidade nem a independência da frequência histórica da oferta atual.

Disputas novas: recuperação × criação, recuperação, remarcação, bloqueio, capacidade zero, remoção de oferta e regularização factual nos três limites temporais; fato × fato, correção com versão antiga, cancelamento administrativo, criação e confirmação de grupo conflitante. Remarcação e cancelamento administrativo sem mudança podem ter duas confirmações quando a sequência resultante é legítima; os testes verificam o estado final, não apenas status HTTP. Nos conflitos de oferta há somente um vencedor.

Números finais e conflitos de versão separados estão na seção 26. Falhas técnicas de lock/timeout não são contabilizadas como recusas funcionais aprovadas: o suporte reprova a mensagem técnica do handler global e exceções inesperadas.

## 19. Atomicidade e rollback

Cinco injeções de falha após escritas reais: recuperação após alteração da reserva e após INSERT da auditoria; fato após atualização de frequência, após histórico e após INSERT da auditoria. Conferência de snapshot de todas as tabelas, inclusive paciente, Envers e auditorias, demonstra rollback integral. Spies apenas introduzem a falha; escritas anteriores são reais em Spring/JPA/H2.

## 20. Repetição e versão

Versão obsoleta retorna 409 antes da comparação de estado/novo resultado. Repetir recuperação/primeiro fato retorna 409 sem evento/auditoria adicional, inclusive com versão atualizada porque a origem deixou de ser CANCELADA+NR. Na correção P↔F posterior, no-op com versão atual mantém 200 sem escrita/auditoria; com versão antiga é 409. Duas requisições simultâneas não criam duas origens factuais ou duas recuperações para a mesma versão.

## 21. Indicadores

Frequência canônica alimenta recálculo cronológico existente: última presença, faltas consecutivas, classificação, presença/falta em grupo e seleção de busca ativa. Testes novos verificam segunda falta AMARELO, terceira VERMELHO, presença histórica seguida de faltas posteriores e presença posterior em grupo. Nenhuma política de risco, SQL clínico de contagem, relatório, RF22 ou RNF17 foi reescrita.

## 22. Visita domiciliar

Par original ausente + sucessora P05 ausente regularizada ativa o gatilho; correção comprovada da sucessora para presença recalcula e desativa quando o critério desaparece. Presença posterior em grupo também reorganiza a sequência e a busca ativa. A política da Etapa 2 é chamada, sem regra paralela. Os cenários preexistentes de pares posteriores válidos continuam na suíte integral.

## 23. Compatibilidade P03/P04/P05

Grade e turno obrigatórios para recuperar; capacidade e disponibilidade não são ignoradas. P04 é preservada por locks compartilhados e proteção de colisão clínica. P05 mantém original, flag após falta, identidade e unicidade da sucessora, e recusa bifurcação, ciclo, raiz contraditória e ancestral de outro paciente. Teste novo percorre HTTP real de FALTOU → remarcação → cancelamento → recuperação REMARCADA. Nenhum novo atendimento retroativo é criado.

## 24. Migração aditiva V7

V1–V6 permanecem byte a byte iguais. V7 cria somente a trilha nova e suas proteções; acrescenta unicidade redundante da identidade existente para suportar FK composta, sem atualizar dados. Não há backfill de regularizações, motivos ou frequências desconhecidas. H2 em memória descartável recebe proteções equivalentes pelo schema guard; H2 persistido exige ferramenta explícita, com startup falhando fechado quando a proteção não existe. Não há migração automática na inicialização da aplicação persistida.

Testados bancos antigo/v0, V4 e V6, migração 7/3/1 passos, reaplicação zero, preservação de dados e auditorias antigas, CHECKs/FKs e trigger. Remover trigger faz verificação e ferramenta falharem sem reparo silencioso. O artefato empacotado também foi exercitado separadamente, conforme seção 26. Seus fixtures mantêm 12 atendimentos, quatro pendentes, três ocupantes; a cópia V4 mantém as duas auditorias clínicas antigas, responsável comprovado da sessão 1 e responsabilidade pendente da sessão 2. A trilha nova inicia com zero linhas e os três triggers protegem seis operações UPDATE/DELETE.

## 25. Reinicialização e restauração

Ciclo com banco H2 em arquivo descartável: backup inicial → criação HTTP/cancelamento/recuperação reais → regularização factual real → correção P↔F real → backup mais recente → shutdown/reabertura → restauração em outro destino. Três inicializações de aplicação aprovadas. Dois registros novos de regularização e um de correção preservados; versões 2, estados finais corretos, trigger/FKs e snapshot completo, incluindo Envers, conferidos. Restauração do backup inicial contém zero regularizações posteriores: não foi usada como prova de preservação de escritas que não continha.

## 26. Execução e matriz de testes

Execuções finais sobre o código definitivo, sem skipTests. JDK 21.0.13; Maven Wrapper com settings/cache locais. Comandos de projeto: `bash ./mvnw test` e `bash ./mvnw verify` (opções locais de settings/cache não mudam a seleção de casos). O verify herdou perfil dev e credenciais exclusivamente sintéticas; o isolamento de testes prevaleceu e nenhum banco de desenvolvimento/real foi utilizado.

| Conjunto | Executados | Aprovados | Reprovados | Ignorados |
|---|---:|---:|---:|---:|
| Baseline inicial verify | 726 | 726 | 0 | 0 |
| Casos anteriores retidos em cada rodada final | 726 | 726 | 0 | 0 |
| Novos: recuperação | 23 | 23 | 0 | 0 |
| Novos: fato e correção posterior | 23 | 23 | 0 | 0 |
| Novos: segurança, autorização, auditoria e rollback | 39 | 39 | 0 | 0 |
| Novos: bypass legado | 5 | 5 | 0 | 0 |
| Novos: ambiguidades e colisões | 19 | 19 | 0 | 0 |
| Novos: indicadores | 5 | 5 | 0 | 0 |
| Novos: migração/persistência/restauração | 7 | 7 | 0 | 0 |
| Novos: concorrência | 25 | 25 | 0 | 0 |
| Total de casos novos | 146 | 146 | 0 | 0 |
| mvnw test final | 872 | 872 | 0 | 0 |
| mvnw verify final | 872 | 872 | 0 | 0 |
| Angular unitários | 104 | 97 | 7 | 0 |

Recortes e execuções repetidas não devem ser somados. Comparação por classe/nome nos XMLs Surefire: **726 anteriores retidos, nenhum ausente, 146 novos, 872 no total, 51 classes** em test e verify. Os testes têm asserções de persistência, autorização e transação; não apenas mock de retorno do serviço.

| Execução final | Disputas reais | Operações | Confirmadas | Rejeitadas |
|---|---:|---:|---:|---:|
| test | 93 | 206 | 109 | 97 |
| verify | 93 | 206 | 109 | 97 |

Cada rodada inclui 68 disputas preexistentes/156 operações e 25 novas/50 operações. Todas têm pelo menos duas conexões independentes. A classificação das **novas** respostas foi instrumentada separadamente:

| Execução | Confirmações | Recusas por regra/estado/tempo | Conflitos explícitos de versão | Falhas técnicas inesperadas |
|---|---:|---:|---:|---:|
| test | 26 | 18 | 6 | 0 |
| verify | 25 | 17 | 8 | 0 |

Variações entre confirmações e recusas refletem a ordem legítima de serialização, preservando os invariantes verificados. Os conflitos técnicos reconhecidos pelo handler global não são aceitos como sucesso de uma disputa funcional.

Sete novos casos de migração/persistência foram aprovados, incluindo as três origens v0/V4/V6. O cenário de ciclo persistido contém três inicializações de aplicação e duas restaurações (backup recente com escritas novas e backup inicial sem elas). Não são sete testes adicionais aos 146 novos. O CLI do JAR final passou em dois bancos descartáveis adicionais: migrou 7 passos do legado e 3 da V4; reaplicação 0/0, dados e auditorias antigos preservados, zero auditorias novas presumidas e seis operações protegidas pelos triggers.

Evidência local detalhada: `/tmp/sibraps-etapa5/baseline`, `final-test-confirmed`, `final-verify-confirmed`, `packaged-migration-final`, `frontend` e `evidence.json`. Logs/XMLs não são versionados porque incluem metadados de ambiente; este relatório registra os resultados agregados. O código dos testes está na branch para reprodução.

Cobertura por classe (todos os casos usam dados sintéticos):

| Casos exigidos | Evidência principal |
|---|---|
| RR-01–RR-18 | CancelRecoveryIntegrationTests; SecurityAudit para motivo; MigrationPersistence com create/cancel reais |
| RR-19 / RR-20 | CancellationConcurrencyTests / CancellationSecurityAuditTests |
| RF-01–RF-09 / RF-13–RF-15 / RF-18–RF-22 | CancelledFactualIntegrationTests; SecurityAudit |
| RF-10–RF-12 / RF-16–RF-17 | CancellationAmbiguityCollisionTests; dados temporais/DST e migração pendente |
| RF-23–RF-24 | CancellationSecurityAuditTests, snapshot após escrita real |
| IH-01–IH-13 | CancelledFactualIntegrationTests e CancellationIndicatorsTests; regressões prévias da Etapa 4 |
| IH-14–IH-15 | MigrationPersistence e SecurityAudit, snapshots incluindo Envers e trilhas |
| IH-16 / CS-11–CS-12 | SecurityAudit e matrizes de perfis de ambos os fluxos |
| CS-01–CS-03 | CancellationLegacyBypassTests e serviços diretos de SecurityAudit |
| CS-04–CS-07 | 726 regressões anteriores, sucessora HTTP real e correção posterior auditada |
| CS-08–CS-10 / CS-16 | CancellationConcurrencyTests e repetição/versão nas integrações |
| CS-13–CS-15 | Cinco pontos de falha reais de SecurityAudit |

Expectativas antigas adaptadas sem remoção de cenários: três classes de testes de migração reconhecem V7/mais um passo; `HistoricalAttendanceReviewTests` passa a recusar reativação genérica; `AgendaConcurrencyTests.p0408ReactivationCompetesWithCreation` usa recuperação específica com motivo/versão e data futura; `SpecificFrequencyCorrectionTests.cf06` exige pendência 409 para CANCELADA+P/F sem origem confiável. A primeira frequência comum e as demais correções mantêm a regra anterior. Comparação dos XMLs por classe/nome confirma retenção de todos os casos antigos.

## 27. Angular

Executados Node 24.19/npm 11.6.2 fixados, npm ci, npm run build e npm test -- --watch=false. Instalação/build aprovados; bundle inicial 847,77 kB, warning preexistente acima de 500 kB. Unitários: 104 executados, 97 aprovados, sete reprovados, zero ignorados e cinco erros não tratados preexistentes. Falhas: App/título; ActivatedRoute em MainLayout, DetalheProfissional, Inicio e Sidebar; dois casos de DetalhePaciente por mock sem buscarDetalhe. Mesma assinatura/contagem da Etapa 4 e frontend integralmente preservado pelo manifesto SHA-256. Não foram corrigidos nesta etapa nem declarados aprovados.

## 28. Arquivos e integridade do escopo

Inventário nominal: **38 arquivos**, sem exclusões.

### Produção Java (20)

- `backend/buscaativa/src/main/java/com/pet/buscaativa/controllers/AgendamentoController.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/entities/Agendamento.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/entities/AuditoriaRegularizacaoCancelamento.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/AuditoriaRegularizacaoDTO.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/RecuperarReservaCanceladaDTO.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/RegularizarFrequenciaCanceladaDTO.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/migration/AuditoriaSchemaGuard.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/AgendamentoRepository.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/AuditoriaCorrecaoRepository.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/AuditoriaRegularizacaoRepository.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/services/AgendamentoService.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/services/AuditoriaRegularizacaoService.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/services/HistoricoPacienteService.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/services/PoliticaTemporalFrequencia.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/AgendamentoServiceImpl.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/BloqueioAgendaServiceImpl.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/DisponibilidadeExcecaoServiceImpl.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/DisponibilidadeServiceImpl.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/HistoricoPacienteServiceImpl.java`
- `backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/UsuarioContextService.java`

### Migração (1)

- `backend/buscaativa/src/main/resources/agenda-migration/h2/V7__auditoria_regularizacao_cancelamento.sql`

### Testes e suporte (15)

- `backend/buscaativa/src/test/java/com/pet/buscaativa/AgendaConcurrencyTests.java`
- `backend/buscaativa/src/test/java/com/pet/buscaativa/AgendaLegacyMigrationTests.java`
- `backend/buscaativa/src/test/java/com/pet/buscaativa/CancelRecoveryIntegrationTests.java`
- `backend/buscaativa/src/test/java/com/pet/buscaativa/CancellationAmbiguityCollisionTests.java`
- `backend/buscaativa/src/test/java/com/pet/buscaativa/CancellationConcurrencyTests.java`
- `backend/buscaativa/src/test/java/com/pet/buscaativa/CancellationIndicatorsTests.java`
- `backend/buscaativa/src/test/java/com/pet/buscaativa/CancellationLegacyBypassTests.java`
- `backend/buscaativa/src/test/java/com/pet/buscaativa/CancellationMigrationPersistenceTests.java`
- `backend/buscaativa/src/test/java/com/pet/buscaativa/CancellationRegularizationSupport.java`
- `backend/buscaativa/src/test/java/com/pet/buscaativa/CancellationSecurityAuditTests.java`
- `backend/buscaativa/src/test/java/com/pet/buscaativa/CancelledFactualIntegrationTests.java`
- `backend/buscaativa/src/test/java/com/pet/buscaativa/ClinicalAuditMigrationTests.java`
- `backend/buscaativa/src/test/java/com/pet/buscaativa/GroupResponsibilityMigrationTests.java`
- `backend/buscaativa/src/test/java/com/pet/buscaativa/HistoricalAttendanceReviewTests.java`
- `backend/buscaativa/src/test/java/com/pet/buscaativa/SpecificFrequencyCorrectionTests.java`

### Documentação (2)

- `docs/etapa5-regularizacao-cancelamentos-incorretos.md`
- `docs/plano-etapa0-fluxos-assistenciais.md`

Manifesto SHA-256 inicial: 166 arquivos protegidos conferidos e zero alterações, incluindo frontend/workflow, V1–V6, segurança/JWT, handler global, política de ocupação, serviço/repositório canônicos do paciente e política de visita. Além das adaptações expressamente listadas, nenhum teste antigo foi removido. JWT, console H2, frontend, workflow, handler global, políticas de risco/visita e V1–V6 permanecem preservados. Configuração de oferta foi alterada somente para a serialização necessária à integridade da recuperação, com regressões dos módulos executadas.

## 29. Commits e publicação

| Commit | Responsabilidade |
|---|---|
| cb8ea6b | Matriz inicial e DTOs de contrato. |
| 002ec93 | Auditoria própria, V7, domínio, temporalidade e histórico. |
| d6abf61 | Recuperação/fato, correção posterior confiável e proteção do legado; integrações e segurança. |
| 85f8961 | Lock compartilhado da oferta; regressões de colisão, indicadores, concorrência e persistência. |
| 1732883 | Asserções explícitas das revisões Envers no ciclo persistido. |

O último commit documental agrega os resultados completos de validação e o plano atualizado; seu hash consta da entrega final.

A revisão documental final é um commit posterior contendo este relatório e o adendo ao plano. O hash desse próprio commit é informado na resposta de entrega, evitando referência circular. Checkout final deve estar limpo e origin/branch igual ao HEAD. Main e a branch original P03/P04/P05 são verificadas antes/depois do push.

## 30. Limitações e classificação

**APROVADA PARA REVISÃO**, pelos critérios da Etapa 5. Backend e build Angular aprovados; as falhas unitárias Angular preexistentes estão explicitadas, sem ocultação ou alteração de escopo.

| Categoria | Comportamentos |
|---|---|
| Preservados | P03/grade, capacidade por turno, bloqueios, P05/identidade da remarcação, isolamento por unidade/USF, cancelamento administrativo sem frequência, registro inicial comum pela recepção, correção clínica comum, indicadores canônicos, risco/visita, histórico e Envers, constraints/auditorias anteriores. |
| Alterados intencionalmente | Recuperação genérica de CANCELADA agora recusada em toda data; dois fluxos específicos com motivo/versão/auditoria; primeiro fato passado autorizado sem oferta atual; correção posterior de CANCELADA somente com origem auditada; colisão por presença canônica cancelada protegida também na criação; escritas de oferta/bloqueio serializadas com a recuperação. |
| Exigem decisão/procedimento da Etapa 6 | Colisões assistenciais reais, evento duplicado/contraditório, dados temporais ambíguos, migração pendente, cadeia incoerente, origem factual ou responsabilidade histórica sem prova. Não houve resolução automática desses casos. |

H2 não certifica outro SGBD; nenhuma implantação, banco real, CI remota ou prontidão de produção foi afirmada. Falhas Angular conhecidas e interfaces pendentes são reservadas às etapas próprias. A prova da frequência consiste na declaração explícita e justificada do executor autorizado; o sistema não verifica documentação externa automaticamente. Dados legados incoerentes continuam bloqueados para reconciliação.

## 31. Próximas etapas e respostas explícitas

| Etapa | Pendência preservada |
|---|---|
| 6 — Reconciliação | Resolução autorizada de colisões, registros históricos ambíguos, responsabilidade de sessões antigas e possíveis duplicações clínicas preexistentes, com evidência e auditoria; sem presumir estados ou alterar automaticamente terceiros. |
| 7 — Erros HTTP | Revisão global de exceções/mensagens/respostas, separando negócio, concorrência transacional e infraestrutura; handler global não alterado. |
| 8 — Angular | Interfaces distintas de recuperação e fato, correção, motivos, auditorias, indicadores provisórios, busca ativa, RF22 e RNF17. Sem botão genérico que misture semânticas. |
| 9 — Validação final | Testes Angular, relatórios/indicadores, CI, SGBD definitivo, migração, concorrência, restauração e segurança operacional. |

| Pergunta final | Resposta |
|---|---|
| 1. Recuperação futura funciona? | Sim, em reserva elegível e com oferta. |
| 2. Exige disponibilidade? | Sim; capacidade, grade, turno, horário e bloqueio são verificados. |
| 3. Preserva ID e remarcação? | Sim; vínculo/flag/original preservados e estado derivado da cadeia. |
| 4. Cancelamento incorreto pode ser regularizado com motivo? | Sim, por um dos dois fluxos específicos conforme a temporalidade. |
| 5. Atendimento ocorrido recebe frequência comprovada? | Sim, P/F explícito pelo responsável/admin local, com motivo e histórico íntegro. |
| 6. Reserva permanece CANCELADA com P/F? | Sim, no fluxo factual. |
| 7. Isso não ocupa vaga? | Não ocupa: ocupaVaga=false. |
| 8. Não há atendimento retroativo fictício? | Correto, nenhum INSERT de agendamento no fluxo factual. |
| 9. Projeção legada preservada? | Sim, CANCELADO no fato regularizado. |
| 10. Indicadores usam frequência canônica? | Sim, recálculo e consultas existentes, com regressões aprovadas. |
| 11. Histórico correto? | Sim, um evento no horário assistencial e com o responsável persistido. |
| 12. Auditoria completa? | Sim, executor/responsável, UTC, motivo, identidade, antes/depois e versões. |
| 13. Responsável validado? | Sim, inclusive serviço direto. |
| 14. Admin restrito à unidade? | Sim, inclusive USF individual. |
| 15. Recepção continua sem permissão clínica de regularização? | Sim; registro inicial comum anterior permanece autorizado. |
| 16. Legado e serviços protegidos? | Sim; bypass reproduzido antes e bloqueado depois. |
| 17. Colisões reais identificadas? | Sim; paciente, profissional e grupos, com resposta segura e sem reconciliação automática. |
| 18. P03/P04/P05 preservados? | Sim, com serialização adicional da oferta exigida pela corrida real. |
| 19. Os 726 testes anteriores passaram? | Sim, 726/726, mesmos casos preservados em test e verify. |
| 20. Todos os novos passaram? | Sim, 146/146 em test e verify. |
| 21. Migrações e restaurações passaram? | Sim, sete casos novos, reinicializações/restaurações e CLI do JAR aprovados. |
| 22. Pronta para revisão? | Sim, APROVADA PARA REVISÃO; sem declaração de produção. |
| 23. Pendências da Etapa 6? | Colisões e ambiguidades, autoria histórica pendente e possíveis duplicações preexistentes; implementação não iniciada. |
