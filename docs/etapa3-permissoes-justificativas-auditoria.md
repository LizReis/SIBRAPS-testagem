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
