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
