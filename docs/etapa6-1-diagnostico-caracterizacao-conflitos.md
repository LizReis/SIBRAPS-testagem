# SIBRAPS — Etapa 6.1: diagnóstico e caracterização de conflitos assistenciais

**Data:** 17/11/2026 (America/Bahia). **Escopo:** rodadas 6.1A–E; somente testes sintéticos e documentação. **Parecer:** diagnóstico inicial consolidado, apto à revisão para iniciar 6.2; cobertura residual explicitamente aberta. Não é aprovação integral da Etapa 6 ou certificação de produção.

## 1. Referências, fontes e preservação

Repositório `LizReis/SIBRAPS-testagem`, branch `correcao-fluxos-assistenciais-sibraps`, base `9a58f80179381186e39dc12d9de1f455b288028c`. Conferência remota somente leitura: nenhuma PR aberta; referências preservadas:

| Referência | SHA |
|---|---|
| main | `967d5162dd543ec9005608739128423903e75e30` |
| correcao-seguranca-p01-p02 | `e772f2b4035ca75574d05fe083009992ff48aa29` |
| correcao-estabilidade-agenda-p03-p04-p05 | `05ee61a5123dfe4e914d7c2f5f486f7ecf310164` |
| correcao-fluxos-assistenciais-sibraps | `9a58f80179381186e39dc12d9de1f455b288028c` |

Fontes: código e árvore Git da base; relatórios `plano-etapa0-fluxos-assistenciais.md`, `etapa2-separacao-reserva-frequencia.md`, `etapa3-permissoes-justificativas-auditoria.md`, `etapa3-1-ajustes-antes-etapa4.md`, `etapa4-correcao-frequencia-retroativa.md`, `etapa5-regularizacao-cancelamentos-incorretos.md` e `etapa5-1-ajustes-antes-etapa6.md`; especificação 6.1 e decisões da desenvolvedora; seis fontes Java contidas nos três instaladores; saídas filtradas Maven das três rodadas fornecidas pela desenvolvedora. Não foi localizado documento autônomo de requisitos na árvore da base; RF22/RNF17 são tratados conforme regras aprovadas e documentação anterior, não como transcrição de uma especificação externa. Não houve acesso a prontuários ou banco real.

Baseline reportado: 925 testes backend aprovados (0 falhas/erros/ignorados), JAR empacotado. A última regressão foi na 5.1; NÃO foi repetida na 6.1. Angular: build anterior aprovado, 97/104 testes aprovados, 7 falhas e 5 erros não tratados preexistentes; alertas de dependências e validação de SGBD/CI permanecem na 9. Nenhum frontend, serviço, DTO, entidade, configuração de produção ou migração V1–V7 foi intencionalmente alterado. A verificação da árvore local exata é condição do fechamento e será realizada pelo instalador de documentação; não foi inspecionada diretamente pelo revisor no Codespaces. Não houve commit, merge ou push pela assistência nesta subetapa.

## 2. Decisões de negócio vinculantes

- **Contagem unificada exclusivamente**, reafirmada pela desenvolvedora com base nas entrevistas com as preceptoras em 17/11. Qualquer presença comprovada, individual ou coletiva, zera as faltas consecutivas anteriores; cada falta legítima posterior acrescenta uma unidade, sem separar contadores por modalidade. Baixa adesão a uma modalidade não autoriza mudar essa regra. Não criar contadores específicos nem novo limiar clínico.
- Ordem por data/hora assistencial, não instante de digitação. Faltar a um grupo e comparecer à consulta em dias/horários diferentes (ou vice-versa) é comportamento legítimo. Preservar ambos os fatos. Se os horários são iguais e não há ordem confiável, não desempatar por modalidade/ID como se fosse ordem clínica comprovada; requer revisão quando afetar indicadores. Ainda não está decidido como resolver mecanicamente um empate de fatos comprovados.
- Preservar 2 faltas para AMARELO e 3 ou mais para VERMELHO, considerando demais critérios de risco existentes; não mudar limites temporais (individual >60/>120 dias, grupo/AMBOS >15/>30). Presença comprovada interrompe sequência anterior; cancelamento sem fato e frequência desconhecida não são faltas presumidas. Manter estado e seleção operacional de pacientes ativos conforme regras anteriores.
- Visita domiciliar/busca em ponto de referência: falta original seguida de falta em remarcação legítima vinculada, sem presença que invalide a sequência. Gatilho não é sinônimo de risco vermelho; preservar textos RF22 e revisão provisória. Uma cadeia inválida ou ambígua não confirma um alerta.
- Impedir reservas incompatíveis entre individuais e grupos nos dois sentidos, por paciente e por responsável; impedir sessões incompatíveis de grupos distintos. Múltiplos participantes da mesma sessão são legítimos. Não confundir presença com ausência, nem ocupação com fato histórico. Horário registrado não prova duração.
- Registro duplicado comprovado deixa de contribuir indevidamente para indicadores e totais pertinentes, mas não será excluído. Preservar identidade, evidência, histórico e versões; registrar decisão, autor, momento, justificativa, vínculo com fato válido. Coincidência de horário não prova duplicidade.
- Frequência ou responsabilidade histórica desconhecida só pode ser resolvida com evidência suficiente, declaração justificada por usuário autorizado e auditoria. Não atribuir automaticamente coordenador atual, nem inventar horários, resultados ou auditorias retroativas.
- Abertura da pendência persistente separada da decisão; não altera os atendimentos. Recusa de reserva ordinária não é automaticamente uma pendência clínica. Sem evidência, permanece pendente. Nenhuma alteração automática de terceiros.
- Preservar RNF17: consulta da lista, confirmação explícita de apresentação e encaminhamento formal continuam eventos distintos; nenhum cria presença, falta ou encaminhamento fictício. (A referência funcional relevante a busca/visita é RF22.)

### Permissões aprovadas para futura reconciliação

| Operação | Responsável | Administrador local | Recepção |
|---|---|---|---|
| Consultar pendências autorizadas/auditoria clínica | Sim | Sim | Não |
| Abrir pendência/apresentar evidências | Sim | Sim | Não |
| Resolver somente próprios atendimentos | Sim | Sim | Não |
| Resolver conflitos de responsáveis distintos | Não; encaminhar ao admin | Sim | Não |
| Resolver outras unidades/USFs | Não | Não | Não |

Sem autenticação: 401; acesso fora de escopo: 404; sem permissão no escopo: 403. Conflito: 409, validação: 422 conforme política existente; revisão global na 7. A matriz futura não foi implementada/testada como endpoint, pois ainda não existe.

## 3. Mapeamento técnico da base

Caminhos relativos a `backend/buscaativa/src/main/java/com/pet/buscaativa/`.

| Componente | Guarda/semântica atual | Lacuna relevante |
|---|---|---|
| `entities/Agendamento.java`, `PoliticaOcupacaoAgendamento.java` | Reserva AGENDADA/REMARCADA/CANCELADA/SUBSTITUIDA e frequência NAO_REGISTRADA/PRESENTE/FALTOU, projeção legada e ocupaVaga; migracaoPendente | Setter legado permite estados de remarcação sem garantir cadeia; quatro casos HTTP confirmados. |
| `services/impl/AgendamentoServiceImpl.java` | `save`, `validarVaga`, `validarConflitosDeHorario`, `aplicarAtualizacao`, `bloquearAgendamento`, `validarCadeiaRegularizacao` | Checagem de grupo depende de ID não nulo na validação comum; registro inicial de presença não percorre todas as guardas factuais. |
| `repositories/AgendamentoRepository.java` | `existsAgendamentoAtivoNoMesmoHorario`, `existsAtivoDoPacienteNoMesmoHorario`, `existsPresencaDoProfissional/Paciente`; consultas de ocupação e presença canônica independentes | Consumidores futuros precisam distinguir presença factual, reserva ativa, excluídos por reconciliação e dados inconclusivos. |
| `entities/GrupoTerapeutico.java`, `SessaoGrupo.java`, `SessaoGrupoParticipante.java` | Sessão AGENDADA/REALIZADA/CANCELADA; responsável consolidado na confirmação, pendência histórica; unicidade sessão+paciente | Coordenação atual não comprova responsável de sessão vencida; restrição por sessão não garante exclusividade global. |
| `services/impl/GrupoTerapeuticoServiceImpl.java` | `criarGrupo`, `atualizarGrupo`, `criarProximaSessao`, `adicionarParticipanteInterno`, `bloquearSessao`, `inscreverEmSessoesFuturas`, `confirmarOcorrencia`, `corrigirFrequencias`, `inscreverRetroativamente` | Guardas e locks divergentes; confirmações/correções/retroativas podem criar presenças incompatíveis. |
| `repositories/SessaoGrupoRepository.java` | `existsParticipacaoNoMesmoHorario` exclui canceladas, mas não distingue ausência e presença em realizadas; `existsSessaoDoResponsavelNoMesmoHorario` usa coordenador atual de agendada e responsável histórico de realizada | Não confundir participação histórica FALTOU com presença factual; definir matrizes separadas para reserva e fato. |
| `services/impl/PacienteServiceImpl.java` | `recalcularAssiduidadePaciente`: eventos canônicos e grupo realizado; ordena data/hora, origem, ID; presença zera, falta incrementa; pendência considera migração individual | Empates artificiais, faltas duplicadas contadas, grupo realizado NAO_REGISTRADA sem sinalização. |
| `services/PoliticaVisitaDomiciliar.java` | Confere faltas, vínculo, flag, paciente e cronologia; não verifica todo estado da cadeia | RF-07 reproduzido com origem não SUBSTITUIDA. |
| `services/impl/HistoricoPacienteServiceImpl.java` | `consultar` conta tipos de eventos; `registrarAlteracao` escolhe primeiro fato por agendamento; `validarHistoricoRegularizacao` valida unicidade/coerência no fluxo de cancelado; correção de grupo usa evento correspondente | Contagens divergentes; correção comum de contradição aceita. Tipo de acompanhamento atual pode influenciar evento individual: rever sem alterar silenciosamente classificação histórica. |
| `services/AutorizacaoClinicaService.java`, `services/impl/EscopoUnidadeService.java` | Escopo, responsabilidade, administrador local, recepção inicial individual limitada; responsabilidade consolidada em grupo realizado | Reutilizar e validar matriz futura, principalmente conflitos envolvendo outra unidade/USF. |
| `services/PoliticaTemporalFrequencia.java`, `config/AgendaHorarioProperties.java`, Clock | Fronteiras temporais, hora local institucional America/Bahia, grade individual configurável (intervalo padrão 30 min) | Grade não é duração clínica confiável de consulta ou grupo; sem campo de duração nos modelos inspecionados. Testes novos são de mesmo instante, não de sobreposição parcial. |
| `services/AuditoriaRegularizacaoService.java`, auditorias clínicas/Envers V1–V7 | Proteção de origem factual do cancelado, motivos/versões e trilha | Nenhuma fila persistente nova. `PendenciaFrequenciaException`/handler indicam recusa, não resolução ou encaminhamento. |
| `IntegridadeOfertaService`, disponibilidade/exceções/bloqueios | Proteções origin/destino e elegibilidade 5.1 | Não redesenhar oferta nesta etapa; revisar ordem global de locks ao integrar grupos. |
| `RelatorioServiceImpl`, `ExcelRelatorioService`, `RelatorioEmailBuilder`, controllers de relatório/busca | Consumidores a revisar para supressão lógica e pendência | Não foi realizada nova caracterização completa de PDF/Excel/e-mail/RNF17 nesta subetapa; cobertura anterior preservada. |

### Operações, contratos e bloqueios relevantes

| Operação HTTP | Serviço e locks observados | Efeito esperado/observado |
|---|---|---|
| POST `/api/agendamentos` criação e remarcação | `save`: paciente → profissional de destino → original se houver; admin/recepção | Cria reserva/histórico; remarcação substitui original e cria sucessor. IG-01–04 falham. Validar origem e ordem do vínculo na prevenção. |
| POST `/api/agendamentos/{id}/regularizacao-cancelamento/recuperar-reserva` | paciente → profissional → atendimento; responsável/admin; versão, motivo, oferta, cadeia | Mesmo ID, ocupação recuperada, auditada, sem frequência inventada. IG-05/06 passam; CO-05 falha com criação de grupo. |
| POST `/api/agendamentos/{id}/regularizacao-cancelamento/frequencia` | mesmos locks; responsável/admin; canônico, cadeia, horário, histórico | CANCELADA permanece, sem vaga; fato+histórico+indicadores+auditoria. Baseline protege colisões; CO-11 produz fatos incompatíveis. |
| PATCH `/api/agendamentos/{id}/status` | `aplicarAtualizacao` sob locks individuais; autorização dependente do comando/fato | Registro inicial operacional/clínico e legado; IND-11 e RM-02–05 falham. |
| PATCH `/api/agendamentos/{id}/frequencia/correcao` | mesmo método, versão e justificativa; responsável/admin | Corrige fato conhecido, recalcula indicadores e audita; DP-04 falha; K-07 e casos anteriores de colisão passam. |
| POST `/api/grupos` e POST `/api/grupos/sessoes` | `criarGrupo`/`criarProximaSessao` leem coordenador/grupo e pacientes; não compartilham todos os locks individuais | Guarda de recorrência/lista e participação em grupo, falta checagem individual/coordenador; GI-03/04/05/05-NEXT, GR-02 falham. |
| PUT `/api/grupos/{grupoId}` | `atualizarGrupo`: lock grupo, versão, autorização; pode regenerar série futura e trocar coordenador | GI-06 e GR-07 falham. Regeneração e lock de pacientes/coordenadores exigem testes adicionais. |
| POST `/api/grupos/sessoes/{sessaoId}/participantes` | `bloquearSessao`: pacientes por UUID → grupo → sessão; escopo | GI-01 falha; GR-01/09 passam. |
| POST `/api/grupos/{grupoId}/inscricoes-futuras` | paciente → grupo; verifica todas as futuras antes de incluir | GI-02 rejeita toda série com conflito; preserva alterações. |
| POST `/api/grupos/sessoes/{id}/confirmacao-ocorrencia` | pacientes ordenados → grupo → sessão; valida coordenador e tempo | Registra resultado efetivo, histórico, reclassificação; GI-07 falha. Cancelamento sem fato e ID/versão mantidos segundo contrato. |
| PATCH `/api/grupos/sessoes/{id}/participantes/{pacienteId}/presenca` | `registrarPresenca`: mesmos locks, valida tempo/autorização, frequência provisória | Não equivale a confirmação; cobertura de colisão provisória residual. |
| PATCH `/api/grupos/sessoes/{id}/frequencias` | mesmos locks + incremento de versão sessão; responsável histórico/admin; justificativa | GI-08/GR-10 falham; NAO_REGISTRADA bloqueada. |
| POST `/api/grupos/{grupoId}/inscricoes-retroativas` | paciente → grupo; valida sessões passadas, autorização, grava participação e auditoria retroativa | GI-09/GR-10-RETRO e CO-11 falham; testar lote passado+futuro. |

A ausência de lock ou consulta em um caminho é evidência estática, não prova de falha até exercício do caso. Não foi definida nova ordem global nesta subetapa.

## 4. Metodologia, inventário e execução

Classes novas em `backend/buscaativa/src/test/java/com/pet/buscaativa/`, todas com sufixo `Characterization` (execução opt-in). O POM não define inclusões especiais de Surefire; logs mostram Surefire 3.5.5. A convenção padrão não inclui o sufixo; não houve alteração de POM ou `@Disabled`. Não foi executada uma suíte padrão nova para comprovar a seleção depois da instalação. Antes do fechamento da Etapa 6, promover casos corrigidos e controles pertinentes à regressão permanente; não perpetuar testes verdes observacionais como contratos do defeito.

| Classe (sufixo 61Characterization) | Rodada | Executados | Passaram | Falharam | Erros | Ignorados |
|---|---:|---:|---:|---:|---:|---:|
| IndividualGroupCollision | 1 | 26 | 14 | 12 | 0 | 0 |
| ReschedulingChain | 1 | 4 | 0 | 4 | 0 | 0 |
| HistoricalCollision | 2 | 11 | 2 | 9 | 0 | 0 |
| ClinicalIndicators | 2 | 7 | 6 | 1 | 0 | 0 |
| ReconciliationConcurrency | 3 | 12 | 5 | 7 | 0 | 0 |
| RemainingAmbiguity | 3 | 3 | 2 | 1 | 0 | 0 |
| **Total** | | **63** | **29** | **34** | **0** | **0** |

23 proteções/contraprovas aprovadas; seis observações aprovadas (DP-01-RF-03, DP-03, SEMANTICA-01, SEMANTICA-02, LG-GRUPO, RESP-09). As falhas são 34 invocações, não 34 causas independentes. As múltiplas asserções `assertAll` não constituem testes adicionais. A etapa não corrigiu nenhum desses defeitos.

Comandos executados no Codespaces (saídas filtradas recebidas, não XMLs completos):

```bash
cd /workspaces/SIBRAPS-testagem/backend/buscaativa
bash ./mvnw -ntp -Dtest=IndividualGroupCollision61Characterization,ReschedulingChain61Characterization test 2>&1 | tee /tmp/etapa61-rodada1.log
bash ./mvnw -ntp -Dtest=HistoricalCollision61Characterization,ClinicalIndicators61Characterization test 2>&1 | tee /tmp/etapa61-rodada2.log
bash ./mvnw -ntp -Dtest=ReconciliationConcurrency61Characterization,RemainingAmbiguity61Characterization test 2>&1 | tee /tmp/etapa61-rodada3.log
```

Todos os três retornaram `BUILD FAILURE` por asserções, nenhum erro de teste/compilação registrado. Tempos por classe reportados: 27,13s/0,797s; 21,26s/1,149s; 19,67s/0,857s (não equivalem ao tempo total de cada comando). Fixtures sintéticas no H2 em memória, Clock do suporte controlado, MockMvc real e snapshots das tabelas públicas onde aplicável. Alguns estados incoerentes são inseridos via fixture/repositório deliberadamente; sua persistência e efeito não provam que foram produzidos por uma API comum. Não enfraquecem constraints. Não alegar generalização para dados reais.

## 5. Violações reproduzidas — catálogo por causa

Gravidade alta provisória em todos os grupos abaixo por impacto na integridade clínica/operacional; nenhum novo caso crítico de acesso interunidades confirmado. IDs de casos preservados para rastrear testes; não renumeram P01–P05. Cada linha representa uma família de causa, não contagem adicional.

| ID de achado | Local/caminho | Resultado esperado e observado | Casos (invocações) | Destino |
|---|---|---|---|---|
| E61-IND-001 | `AgendamentoServiceImpl.save/validarConflitosDeHorario` | 409, sem mudanças; 201 e nova reserva/remarcação com grupo incompatível | IG-01/02/03/04 (4) | 6.2 |
| E61-GRU-001 | `adicionarParticipanteInterno`, criação grupo/sessão | 409; 200 e inscrição/sessão/grupo persistidos apesar de reserva individual | GI-01/03/04 (3) | 6.2 |
| E61-GRU-002 | `criarGrupo`, `criarProximaSessao`, `atualizarGrupo` | 409; 200 e sessão ou coordenação conflitante com consulta ou outro grupo | GI-05/05-NEXT/06, GR-02/07 (5) | 6.2 |
| E61-REM-001 | `aplicarAtualizacao`, setter legado | 409; 200, falso estado de remarcação/substituição sem sucessor | RM-02/03/04/05 (4) | 6.2 |
| E61-HIS-001 | `confirmarOcorrencia`, `corrigirFrequencias`, `inscreverRetroativamente` | 409 e preservação; 200 e alteração efetiva criando presença incompatível | GI-07-PACIENTE/PROFISSIONAL, GI-08/09, GR-10-CORRECAO/RETRO (6) | 6.2 |
| E61-HIS-002 | `aplicarAtualizacao` registro inicial PRESENTE | 409; 200 e nova presença incompatível, inclusive com CANCELADA+PRESENTE | IND-11-PACIENTE/PROFISSIONAL/CANONICO (3) | 6.2 |
| E61-HIS-003 | `registrarAlteracao` escolhe primeiro histórico elegível | 409; 200 e alteração de histórico contraditório, snapshot diferente | DP-04 (1) | 6.2 guardas; 6.4 resolução |
| E61-CON-001 | Guardas/locks operacionais cruzados | Apenas uma confirmação; 2 confirmadas e 2 registros incompatíveis | CO-01/02/03/05/07/08 (6) | 6.2 |
| E61-CON-002 | `regularizarFrequenciaCancelada` × `inscreverRetroativamente` | Apenas uma confirmação; 2 confirmadas, 2 presenças, histórico +2 | CO-11 (1) | 6.2 |
| E61-RF-001 | `PoliticaVisitaDomiciliar.necessaria` | Não confirmar visita por cadeia inválida; visita=true e pendente=false com original AGENDADA | RF-07 (1) | 6.2 impedir novos estados; 6.5/6.6 cadeia histórica/indicadores |
| **Total de invocações vermelhas** | | | **34** | |

### Evidência das remarcações

| Caso | Antes | Depois (reserva/frequência) | Vaga | Sucessor |
|---|---|---|---|---|
| RM-02 | AGENDADO | REMARCADA/NAO_REGISTRADA | sim | não |
| RM-03 | AGENDADO | SUBSTITUIDA/NAO_REGISTRADA | não | não |
| RM-04 | FALTOU | SUBSTITUIDA/FALTOU | não | não |
| RM-05 | PRESENTE | SUBSTITUIDA/PRESENTE | não | não |

Todas as quatro operações foram autorizadas pelo HTTP 200, com snapshot alterado. RM-02 tem origem nula no setup e o teste exige veto à fabricação de REMARCADA; RM-03–05 não têm sucessor. A frequência conhecida não foi apagada, mas a reserva foi adulterada. Não tratar falsa substituição como cancelamento legítimo ou retificar automaticamente o histórico.

## 6. Controles e observações — resultados efetivos

Proteções novas verificadas (23): IG-05/06 (recuperação); IG-09/10/11 (cancelamento e compatibilidade); GI-02/10 (lote futuro e cancelado sem fato); GR-01/03/04/05/06/08/09 (exclusividade paciente, mesma sessão legítima, atores/horários distintos, cancelada, próxima sessão e duplicidade de inscrição); GI-07-CONTROLE/GI-08-CONTROLE (históricos compatíveis); LG-GRUPO-CORRECAO (bloqueio 409 e snapshot igual); CO-04/06/09/10/13 (cinco disputas); K-07 (404, sem alteração/exposição do tipo de conflito, nome ou UUID do paciente no teste).

| Observação | Resultado reproduzido | Interpretação / próxima ação |
|---|---|---|
| DP-01-RF-03 (E61-DUP-001, alta) | 2 faltas/AMARELO → 3/VERMELHO; pendente=false, visita=false; IDs distintos representando mesma falta sintética | Contagem dupla comprovada nesse conjunto. Não prova duplicidade pela hora em casos reais. Seleção efetiva nessa busca ativa ainda não executada. 6.4/6.6. |
| DP-03 (E61-DUP-002, alta) | API do histórico `totalFaltas` 1→2; contador canônico permanece 1, VERDE | Eventos duplicados e registros duplicados exigem tratamento distinto. 6.4/6.6. |
| SEMANTICA-01/02 (E61-SEM-001, alta potencial) | Individual presente/grupo falta: 1 falta; inverso: 0; mesma data/hora, última presença igual, pendente=false | Empate arbitrário por origem. Não é defeito por alternar frequência em datas/horas diferentes. Preservar contagem unificada; decisão de empate em 6.4/6.5. |
| LG-GRUPO (E61-LG-001, alta potencial) | REALIZADA+NAO_REGISTRADA: 0 faltas, última presença nula, VERDE, pendente=false | Não inventa falta; não indica incerteza. LG-GRUPO-CORRECAO recusou alteração comum. 6.3/6.5/6.6. |
| RESP-09 (E61-RESP-001, alta potencial) | Sessão vencida, troca A→B (200), confirmação 200, REALIZADA, atribuição a B, pendente=false | Evidência insuficiente sobre quem a conduziu; comportamento observado não prova B incorreto. Revisão autorizada 6.5; não assumir A nem B. |

Casos RF22/RNF17 prévios permanecem requisito: faltas original+remarcada de cadeia válida, presença posterior, recálculo; busca ativa por vermelho OU gatilho confirmado ou revisão provisória, não confundir. Não há novos eventos RNF17 inventados; não foi feita nova execução de suas rotas. RF-07 comprova falso gatilho, não execução de visita ou envio de alerta externo.

## 7. Concorrência

12 disputas, 24 operações, duas conexões independentes por disputa; 20 confirmações, 4 recusas. Sete disputas incompatíveis confirmaram duas operações e persistiram dois resultados; quatro incompatíveis tiveram uma confirmação, uma recusa; uma compatível teve duas confirmações. Sem erro técnico reportado. O suporte `AgendaIntegritySupport.race` usa transações REQUIRES_NEW, conexão H2 distinta, barreira de início e timeouts; ausência de deadlock nessa rodada não garante todas as interleavings ou outro SGBD. A maioria das corridas não verifica snapshot global integral por operação; verifica contagem e estado-alvo persistido, além de deltas de linhas/histórico, com guardas específicas de cadeia em CO-03 e recuperação CO-04/05.

| Disputa | Confirmadas/recusadas | Persistidos | Delta agendamentos/histórico | Parecer |
|---|---|---:|---|---|
| CO-01 criação × inclusão | 2/0 | 2 | +1/+1 | falha |
| CO-02 criação × grupo coordenador | 2/0 | 2 | +1/+1 | falha |
| CO-03 remarcação × inclusão | 2/0 | 2 | +1/+2 | falha; original SUBSTITUIDA e sucessor existente |
| CO-04 recuperação × inclusão | 1/1 | 1 | 0/0 | passa nesta ordem exercitada |
| CO-05 recuperação × criação grupo | 2/0 | 2 | 0/0 | falha |
| CO-06 duas inclusões grupo | 1/1 | 1 | 0/0 | passa |
| CO-07 duas sessões coordenador | 2/0 | 2 | 0/0 | falha |
| CO-08 transferência × criação | 2/0 | 2 | +1/+1 | falha |
| CO-09 correção × confirmação | 1/1 | 1 | 0/+1 | passa nesta ordem exercitada |
| CO-10 duas correções | 1/1 | 1 | 0/0 | passa nesta ordem exercitada |
| CO-11 regularização × inscrição retroativa | 2/0 | 2 | 0/+2 | falha |
| CO-13 operações independentes | 2/0 | 2 | +1/+1 | passa |

CO-12 (transferência × confirmação) tem cobertura anterior em `GroupHistoricalResponsibilityTests.gr11TransferAndConfirmationNeverAuthorizeFromStaleCoordinator` e `gr11AdminConfirmationFreezesCoordinatorUnderSameDatabaseLock`; não reexecutada aqui. Na 6.2, testar ambas as ordens determinísticas quando viáveis, além de corrida real, rollback de lotes, revalidação após locks, mudanças de elenco/coordenação, evitar deadlocks e checar que recusas não são falhas técnicas travestidas de conflito. Usar locks por paciente e responsável em ordem coerente entre fluxos individuais/coletivos; não basta validar antes de abrir transação.

## 8. Revisão de cobertura da matriz original

Legenda: **N** = nova evidência executada (verde, vermelha ou observação); **B** = proteção anterior identificada no código/testes/relatórios, não repetida; **P** = investigação residual, parcial ou sem nova evidência específica; **D** = depende de decisão, não fixar expectativa arbitrária. As referências B não são novas execuções e não significam cobertura exaustiva da combinação.

| Frente | N: casos exercitados nesta etapa | B: cobertura anterior identificada | P / D: cobertura residual e ação |
|---|---|---|---|
| A — Individual | IND-11 três variantes; IG-05/06 e CO-03–05 adjacentes | IND-01–04/08–09 `AgendaConcurrencyTests`, `AgendaCombinedTests`, `AgendaRescheduleTests`; IND-05–07/10 `FrequencyCollisionIdentificationTests`, `CancellationAmbiguityCollisionTests`, `CancelRecoveryIntegrationTests`, `HistoricalAttendanceReviewTests`, `CancelledFactualIntegrationTests` | IND-12 chamada direta em cada novo caminho, inclusive colisão inicial e canônica; verificar 6.2, reutilizando teste de autorização direta. |
| B — Individual/grupo | IG-01–06/09–11; GI-01–10 e GI-05-NEXT, incluindo GI-07/08/09 históricos e controles | IG-07 `FrequencyCollisionIdentificationTests`; IG-08 `CancellationAmbiguityCollisionTests`; GI-12 `GroupHistoricalResponsibilityTests` | GI-11 controles em fluxo inverso parcialmente cobertos por históricos e horários distintos GR-05/IG-10; repetir por caminho conforme mudança 6.2. Inscrição retroativa mista passado+futuro, regeneração de série e marcação provisória pendentes. |
| C — Grupos | GR-01–10 (GR-10 histórico em duas operações); CO-06/07/08 | GR-11 `GroupHistoricalResponsibilityTests` | CO-12 baseline, criação próxima sessão duplicada do mesmo grupo concorrente e edição estrutural não caracterizadas especificamente. 6.2. |
| D — Semântica/tempo | SEMANTICA-01/02, cancelados, diferentes horários, presenças conflitantes, DP-01, LG-GRUPO | `CanonicalAppointmentTests`, `AttendanceTimePolicyTests`, `CancellationIndicatorsTests` | D: empate, ausência legítima simultânea, duração/sobreposição parcial. Não inventar intervalos; distinguir reservas, presença/falta e incerteza. |
| E — Legado | LG-GRUPO e LG-GRUPO-CORRECAO | LG-01–07 `AgendaLegacyMigrationTests`, `MigratedAgendaHttpTests`, `MigratedIndicatorsInitializationTests`, `RiskVisitMigratedHistoryTests`, `CanonicalAppointmentTests`, `CancellationAmbiguityCollisionTests`; LG-08/09 `CancellationAmbiguityCollisionTests`, `CancelledFactualIntegrationTests` | LG-10 divergência canônico/legado em combinações adicionais e impacto de grupo incompleto; inventário de constraints V1–V7 e caminhos de reconciliação 6.5. |
| F — Duplicidades | DP-01/03/04/10 e semântica; DP-10 efeito no risco, sem consulta específica à seleção | DP-05/06 `CancelledFactualIntegrationTests`, `SpecificCorrectionIndicatorTests`; DP-07/08/09 `AgendaRescheduleTests`, `CancellationIndicatorsTests`, `HomeVisitRecalculationTests` (contraprovas cronológicas, não todos os pares) | DP-02/11/12 duplicidade de presenças, última presença e histórico de grupo; cobertura de dois fatos legítimos no mesmo dia, efeito em relatórios e busca ativa; 6.4/6.6. Não excluir automático. |
| G — Responsabilidade | RESP-09 | RESP-01–07 `GroupHistoricalResponsibilityTests`; RESP-08 `GroupResponsibilityMigrationTests`, relatório 3.1 com seis variantes pendentes | RESP-10 auditoria histórica contraditória; D evidências suficientes para confirmação tardia; 6.5. |
| H — Remarcação | RM-02–05, RF-07 e CO-03 | RM-01/06/07/08 `AgendaRescheduleTests`, `CanonicalAppointmentTests`; RM-09/11 `CancellationAmbiguityCollisionTests` no contexto regularização; RM-12/13 `CancelRecoveryIntegrationTests`, `CancelledFactualIntegrationTests`; RM-15/16 `SpecificCorrectionIndicatorTests`, `HomeVisitRecalculationTests` | RM-10 sucessor não posterior no fluxo normal; reabertura de SUBSTITUIDA sem frequência por legado; RM-14 falta em cancelado regularizado: D. Ciclos/forks também fora do fluxo de cancelado. 6.2/6.5. |
| I — Tempo e proveniência | IND-11-CANONICO e DP-04 complementam | TM-01/02/04–07 `AttendanceTemporalHttpTests`, `AttendanceTimePolicyTests`, `HistoricalAttendanceReviewTests`, `CancellationAmbiguityCollisionTests`; TM-08 `GroupFrequencyTemporalTests` (hora ausente); TM-09/10/11 `CancellationAmbiguityCollisionTests`, `CancellationSecurityAuditTests`, `CancelledFactualIntegrationTests` (origem e auditoria) | TM-03 turno incoerente, data/hora ausente em recálculo de grupo, sequência histórica de auditoria e consumidores adicionais; 6.5/6.6. Não presumir proteção por uma guarda em outro caminho. |
| J — Indicadores | RF-03/07; semântica e LG-GRUPO | RF-01/02/04/05/06/09/10 `SpecificCorrectionIndicatorTests`, `CancellationIndicatorsTests`, `HomeVisitRecalculationTests`; RF-08 `RiskVisitMigratedHistoryTests`; RF-11 `RecoveryEligibility51Tests`, `Etapa51ComplementaryTests`; RF-12 `BuscaAtivaAuditIntegrationTests`, `BuscaAtivaSelectionTests` | Validação de busca ativa/relatórios após exclusão lógica e empates, alertas provisórios sem ocultar fatos, combinações de cadeia inválida; 6.4–6.6. |
| K — Permissões | K-07 (admin externo, correção individual) | `ClinicalAuthorizationTests`, `ClinicalAuditIntegrationTests`, `GroupHistoricalResponsibilityTests`, `IsolamentoUnidadeTests`, `CancellationSecurityAuditTests`, `FrequencyCollisionIdentificationTests` | Novos caminhos/serviços diretos, isolamento de pendências multiunidade, mensagens e possíveis IDs/nome de terceiros em grupos; 6.2/6.3/6.6 e revisão HTTP 7. |
| L — Concorrência | CO-01–11/13 (12 disputas) | CO-12 `GroupHistoricalResponsibilityTests` | Outras ordens, criação em lote versus edição, revalidação de escopo/identidade após locks e rollback de transações cruzadas. 6.2 e 6.6. |

Nenhuma constraint foi removida para simular defeito. A unicidade sessão+paciente é declarada na entidade e testada por GR-09 via API; não é prova de que todas as FKs/checks impossibilitem todo estado legado. Não há inventário de cenários impossíveis exaustivamente validado nesta rodada. Relatórios anteriores registram ensaios V1–V7 e restauração; estes não foram repetidos.

## 9. Decisões em aberto e encaminhamento

| Questão | Encaminhamento |
|---|---|
| Empates reais de frequência no mesmo instante | Preservar fatos autorizados e marcar ambiguidade relevante; não inferir que a última modalidade é a última presença; definir evidência/efeito provisório antes de 6.4/6.5. Não confundir com frequência em dias diferentes. |
| Duas faltas no mesmo instante / identificação de duplicidade | Evidência suficiente + decisão autorizada + vínculo de fato válido; não deduplicar só por horário, nome ou tipo. Definir critérios/auditoria 6.3/6.4. |
| Evidências aceitas e retenção | Definir referência à evidência mínima, acesso restrito/privacidade; sistema não comprova documento externo automaticamente. 6.3. |
| Sessão vencida e troca de coordenador | Exigir revisão fundamentada se autor histórico não demonstrado; como registrar confirmação/pendência de modo atômico dependerá de 6.5. |
| Duração de consultas/grupos | Apenas instantes atualmente; grade de 30 min não prova duração de atendimento. Definir intervalos antes de afirmar detecção de sobreposição parcial. |
| Reservas futuras existentes após inativação/encerramento ou mudança de perfil | Política operacional ainda em aberto; não cancelar automaticamente nem inventar retorno ao ativo. |
| Cancelada+FALTOU como origem de remarcação | Não escolher nesta etapa; preservar restrições vigentes e discutir antes de alterar o fluxo. |
| Conflitos históricos entre unidades | Não permitir que admin local modifique terceiros; definir fluxo institucional seguro sem divulgar dados de outra unidade. |
| Indicadores provisórios | Definir efeito de cada ambiguidade, inclusive última presença conhecida, risco/visita e seleção para revisão; sem ocultar fatos comprovados. |

## 10. Plano de correções e portões de validação

| Subetapa | Escopo |
|---|---|
| 6.2 | Prioridade impedir novos conflitos. Validação compartilhada por paciente/responsável; reserva versus fato; criação, remarcação, recuperação, grupo/sessão/serie, inclusão futura/isolada/retroativa, transferência, confirmação e correções; locks determinísticos e atomicidade; vedar fabricação de remarcação legado e alteração comum de história comprovadamente contraditória. Caracterizar os residuais operacionais antes da respectiva mudança. Não reconciliar legado automaticamente. Preservar correções históricas independentes da oferta atual. |
| 6.3 | Pendência persistente, autorização, idempotência, evidências/versão, encaminhamento e consulta. Abertura sem mudança clínica. |
| 6.4 | Decisão autorizada sobre duplicidades/fatos contraditórios, supressão de contribuição e auditoria preservando original. Testar ambos os consumidores, históricos e contagem unificada. |
| 6.5 | Frequência desconhecida, responsabilidade histórica, cadeia temporal/legado e origem factual confiável. Distinguir recusa de confirmação e revisão; não atribuir dono/resultado sem evidência. |
| 6.6 | Recalcular contagem unificada, última presença, risco e visita somente com política aprovada; RF22/RNF17, relatórios, autorização/concorrência/rollback, migração aditiva quando necessária. Promover caracterizações pertinentes para regressão; comparação nominal contra baseline 925, uma regressão `verify` ampla no encerramento, salvo nova mudança relevante. |
| 7–9 | Erros HTTP globais, Angular, testes frontend/CI, dependências, SGBD, backup/restore e segurança operacional. |

A próxima etapa deverá usar testes direcionados, sem `clean` rotineiro, sem repetição das três suítes diagnósticas integralmente a cada ajuste, sem npm se frontend inalterado. As observações relativas a política não podem ser convertidas em expectativas definitivas até decisão. Novas falhas de compilação/infraestrutura não são defeitos de negócio. Uma recusa precisa preservar estado/versão/histórico/auditoria. Não afirmar proteção integral só por retornar 409; confirmar motivo funcional e estado. As 34 reprovações permanecerão rastreáveis até correção ou decisão explícita, com testes permanentes convencionais após validação.

## 11. Parecer e limitações finais

1. Individual × individual possui cobertura anterior de reserva e correção; novas lacunas de presença inicial foram confirmadas. Individual × grupo e inverso não são uniformes; coordenador pode possuir sessões/consultas incompatíveis. Operações compatíveis foram confirmadas.
2. Legado fabrica remarcação/substituição sem cadeia. Duas faltas com vínculo inválido podem confirmar visita. Reserva e frequência permanecem conceitos distintos.
3. Frequências desconhecidas de grupo não viram falta, mas não sinalizam pendência; registros individuais legados têm guardas anteriores. Duplicidade clínica e de eventos têm efeitos distintos. Histórico contraditório permite correção comum. Empate exige definição específica, não mudança de modelo de contagem.
4. Responsável de sessão já realizada é protegido conforme baseline; confirmação tardia A→B demonstrou atribuição pelo coordenador vigente, sem prova de autoria real. Há guardas anteriores de tempo e proveniência de regularização; não exaurem todas as ambiguidades.
5. K-07 confirma proteção específica interunidade; sem evidência de novo vazamento nesse teste. Não afirmar que todo o sistema está livre de vazamentos. Doze disputas novas revelaram sete violações. Não declarar que as quatro interleavings recusadas tornam a corrida impossível.
6. Os 63 casos e a cobertura anterior são suficientes para priorizar 6.2. A matriz residual acima NÃO foi integralmente executada. Ela constitui trabalho de caracterização obrigatório antes das mudanças correspondentes, e não aprovação das lacunas. A opção por não repetir casos anteriores ou testar todas as combinações agora foi tomada para evitar redundância e uso desnecessário do Codespaces, com risco residual explícito.
7. Não foram obtidos logs XML completos das três rodadas, execução independente, auditoria de banco real, CI ou SGBD definitivo. A verificação dos seis arquivos locais por hash e ausência de mudanças rastreadas depende do script de fechamento. Validação de seleção padrão também depende da regressão final; não executar os 925 novamente somente para fechamento documental.

**DIAGNÓSTICO CONCLUÍDO — AGUARDANDO REVISÃO PARA A ETAPA 6.2**, limitado ao diagnóstico inicial consolidado com pendências de cobertura rastreadas. A publicação das seis classes opt-in e dos dois arquivos de documentação exige revisão local e autorização da desenvolvedora. Nenhuma correção de produção foi iniciada; não realizar merge, force push, PR automática ou mudança na main.
