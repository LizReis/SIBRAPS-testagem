# Etapas 8.3 a 8.5 — Fluxos operacionais do Angular

Base `07a8019a1e446d739b7c6f93b8ecccf8cebecf0a`, na branch `correcao-cadastro-unico-isolamento`. A entrega é o commit que inclui este relatório. A CI de referência aprovou 1.448 testes Java, 12 opt-in de concorrência, 173 testes Angular e 4 testes de contrato experimental, além dos builds padrão e experimental. O pacote reaproveita o inventário e as classificações da entrega anterior. [CI de referência](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45927166999) · [Inventário anterior](etapa8-1-8-2-inventario-integracao-erros.md)

## Implementação e contratos

| Área | Componentes reaproveitados | Contratos operacionais e alterações |
|---|---|---|
| Pesquisa e seleção de paciente | `Pacientes` e `PacienteService` | `GET /pacientes/pesquisa` e resumo local. A busca digitada aplica a resposta à tabela. Digitação, filtros, limpeza e paginação usam a mesma sequência de requisições. Uma nova consulta invalida a anterior imediatamente, inclusive quando aguarda a digitação. |
| Cadastro único e edição | `CadastroPaciente`, `CadastroExistente`, `CadastroUnicoService` e `PacienteService` | Criação `POST /pacientes` e edição `PUT /pacientes/{paciente}/vinculos/{vinculo}/cadastro?versaoCadastro=…` retornam `CadastroLocal`. O tipo contém os identificadores do paciente e vínculo e suas duas versões. O detalhe usa o paciente retornado e confere o vínculo. A edição envia apenas campos cadastrais e exige novo carregamento e confirmação em caso de versão obsoleta. Conferência e vinculação continuam através de `/pacientes/cadastro-unico/conferencia` e `/confirmar-vinculo`, com confirmação humana. |
| Estado clínico local | `DetalhePaciente` e formulários existentes | `GET /pacientes/{paciente}/detalhe` mantém vínculo, unidade e indicadores. A tela separa dados compartilhados de estado clínico, modalidade e indicadores locais. Configurar, encerrar e reativar usam `PATCH` no subrecurso local com versão. Inativar usa `DELETE` no mesmo vínculo. O tratamento de conflito orienta verificar o estado antes de gravar novamente. |
| Reservas e frequências | `Agenda` e `AgendamentosPaciente` | `AgendamentoDTO` mantém `situacaoReserva`, `frequenciaAssistencial` e sinal de conferência. Cartões, lista e resumo apresentam reserva e frequência separadamente. Ausência de valor canônico aparece como não confirmada, distinta de `NAO_REGISTRADA`. |
| Criação, remarcação e status | `Agenda`, `NovoAgendamento` e `AgendamentoService` | `POST /agendamentos`, detalhes do original, sugestões, vagas e horários fornecidos pelo servidor. A rota tem guard de administrador/recepção. A remarcação informa a versão e, após recusa por versão ou resultado incerto, exige atualizar o original e rever as escolhas. O PATCH de status é usado para cancelamento e lançamento inicial, conforme dados e perfil. |
| Regularização e correção | Formulário existente de revisão em `Agenda` | Recuperação e regularização usam os dois comandos em `/agendamentos/{id}/regularizacao-cancelamento`. Correção retroativa usa o PATCH `/agendamentos/{id}/frequencia/correcao`. A interface diferencia o efeito na reserva do registro assistencial, mantém justificativa e usa a versão da operação. |
| Série, coordenação e sessão adicional | `GruposTerapeuticos`, `AgendarGrupoTerapeutico`, `DetalheGrupoTerapeutico` e diálogo existente | `POST /grupos`, `PUT /grupos/{grupo}` e `POST /grupos/sessoes`. A edição por versão distingue estrutura e transferência de coordenação. O diálogo cria sessão adicional por `NovaSessaoDTO`, com data e horário opcional, e navega à listagem da data efetiva. A sessão começa sem inscrições adicionais e sua composição pode ser editada em seguida. A operação de criação não recebe versão do grupo. |
| Participação em série ou sessão | Aba de participantes em `DetalheGrupoTerapeutico` | Inscrição futura e retroativa usam os recursos de série. Inclusão e remoção de sessão usam `/grupos/sessoes/{sessao}/participantes` com `version`. A remoção de série usa `/grupos/{grupo}/participantes/{paciente}` e mantém o histórico. Seleção e confirmação explicitam o alcance. |
| Frequência de grupo e responsabilidade | Aba de ocorrência e diálogo de correção em `DetalheGrupoTerapeutico` | A frequência provisória é gravada explicitamente por participante com PATCH `/grupos/sessoes/{sessao}/participantes/{paciente}/presenca?version=…`, com `RegistrarPresencaGrupoDTO`. Cada resposta atualiza a versão. A gravação de um participante preserva o rascunho dos outros. Confirmação e correção usam `ConfirmarOcorrenciaGrupoDTO` e `CorrigirFrequenciasGrupoDTO`. O DTO de sessão separa coordenador, designação, responsável efetivo, origem e pendência. |

As rotas acima usam a raiz clínica de cada build. As duas variantes atuais mantêm o contrato por vínculo. Formulários, cartões, tabelas e diálogos usam os estilos existentes. A nova sessão foi adicionada no detalhe, sem nova rota. Os tratamentos centralizados das Etapas 8.1 e 8.2 continuam em uso.

## Permissões e recusa

- Administrador, profissional e recepção podem cadastrar e editar informações compartilhadas, com versão e acesso ao vínculo. Configurar, reativar e inativar são ações administrativas. Encerrar acompanhamento é uma ação de administrador ou profissional conforme escopo. O detalhe exibe os indicadores da unidade retornada pela API, com distinção de nulidade e revisão.
- Criação e remarcação individuais exigem administrador ou recepção. Registro inicial e cancelamento seguem o perfil operacional e a autoridade da reserva. Correção, recuperação e regularização exigem perfil clínico e, para o profissional, responsabilidade pelo registro. O backend conserva as guardas temporais e de comprovantes.
- A estrutura da série mantém os perfis existentes e verifica o coordenador no editor quando o ator é profissional. As composições de sessão usam os perfis operacionais e exigem sessão editável. Inscrições e remoções de série seguem as operações clínicas, com autoria exigida no que couber. A confirmação usa designação e autoridade verificadas pelo backend. Correção histórica exige autoria comprovada. Sessões marcadas com pendência de autoria não oferecem confirmação, cancelamento ou marcação provisória.
- Diante de falha incerta de gravação provisória, o usuário precisa consultar o estado persistido. A tela não trata a operação individual como lote atômico. Não há reenvio automático de escritas. Para criação de sessão com resposta incerta, a consulta usa a própria data solicitada.

## Matriz de continuidade

| Funcionalidade | Estado | Próxima ação ou limite |
|---|---|---|
| Pesquisa digitada e concorrência com filtros/páginas | Concluída | Validação integrada posterior |
| Conferência, cadastro, vinculação e edição compartilhada | Concluída | `204` de vinculação continua sem identificadores. Confirmar pelo fluxo existente e voltar à lista |
| Visualização separada e comandos do vínculo | Concluída | A política para reservas futuras após encerramento continua pendente |
| Agenda por reserva/frequência, criação e remarcação | Concluída | Confrontar as guardas temporais no ambiente operacional nas jornadas integradas |
| Cancelamento, recuperação, frequência inicial e correção | Concluída | A trilha detalhada de auditoria por atendimento não está publicada no contrato operacional |
| Edição de séries, transferência e sessão adicional | Concluída | Backend valida escopo, estrutura, disponibilidade e colisões |
| Participantes por série e sessão | Concluída | Confirmar jornadas por perfil na validação integrada |
| Provisória, confirmação e correção de frequências | Concluída | A gravação provisória é individual e não confirma ocorrência |
| Designação, autoria comprovada e proteção RESP-09 | Concluída | A apresentação usa a evidência disponível e mostra inconclusividade |
| Regularização administrativa de autoria sem prova | Bloqueada por decisão e contrato | Requer critério institucional e operação autorizada com trilha de evidência |
| Auditoria detalhada operacional por atendimento e sessão | Bloqueada por contrato | Confirmar campos e publicar leitura autorizada antes da interface do Pacote 3. O endpoint legado de perfil `test` não atende a esse uso |
| Painel de Pendências clínicas e aplicação/reversão de decisões | Não iniciada | Escopo das Etapas 8.6 e 8.7 |

## Arquivos modificados

Em `frontend/sentinela-pet/src/app/`:

- Rotas e perfis em `app.routes.ts` e `auth/permissoes.ts`.
- Cadastro e acompanhamento em `pages/pacientes/pacientes.ts` e seu spec, `pages/cadastro-paciente/cadastro-paciente.ts` com template e spec, `pages/detalhe-paciente/detalhe-paciente.ts` com template e `services/paciente/paciente-service.ts`.
- Agenda em `pages/agenda/agenda.ts` e `pages/novo-agendamento/novo-agendamento.ts`, ambos com templates e specs, `pages/agendamentos-paciente/agendamentos-paciente.ts` com template e `services/agendamento-service.ts`.
- Grupos em `pages/grupos-terapeuticos/grupos-terapeuticos.html`, `pages/agendar-grupo-terapeutico/agendar-grupo-terapeutico.ts` com template, `pages/detalhe-grupo-terapeutico/detalhe-grupo-terapeutico.ts` com template e spec e `services/grupo-terapeutico-service.ts`.
- Requisições Angular nos dois builds em `services/contrato-clinico-integrado.spec.ts`.

O relatório está em `docs/etapa8-3-8-5-fluxos-operacionais-frontend.md`.

## Testes

| Execução | Resultado | Cobertura |
|---|---:|---|
| Angular padrão, seleção inicial de 13 specs | 60 aprovados | Busca, cadastro, permissões, estado local, agenda, grupos e HTTP Angular |
| Angular padrão, rechecagem de 6 specs afetadas | 24 aprovados | Detalhe de paciente, cadastro, agenda/remarcação e telas de grupos. Inclui a nova recusa de versão de remarcação com recuperação explícita |
| Rechecagem do formulário de cadastro após ajuste de navegação | 12 aprovados | Retorno à lista após vinculação e disponibilidade do formulário |
| Angular ensaio, spec do contrato clínico | 5 aprovados | Raiz de ensaio, versão cadastral e de correção, sessão adicional e operações versionadas por participante |
| TypeScript sem emissão e diff | Aprovados nas verificações locais | Compilação e consistência textual |

Na seleção padrão, 61 cenários distintos passaram considerando o novo teste de remarcação. Os demais da rechecagem pertencem à seleção inicial. Uma rodada intermediária falhou na fixture de injeção de Novo Agendamento por uma entrada vazia no array de providers. O teste foi corrigido e a seleção afetada passou. A regressão integral fica a cargo do workflow de entrega. Nenhum teste Java, opt-in de concorrência ou build Angular completo foi executado localmente.

A busca foi testada com requisição anterior atrasada. As fixtures de cadastro verificaram navegação com a identidade e o vínculo da resposta. A agenda cobriu separação de estados e preservação da justificativa na recusa de versão. Os testes de grupo cobriram a versão devolvida em duas gravações provisórias, a guarda RESP-09 e a preservação do rascunho após falha incerta. As requisições foram exercitadas por `HttpTestingController`; as operações de UI usam fixtures. As jornadas ponta a ponta com o backend e o aceite de usabilidade ficam para a validação integrada.

## Limites e continuidade

- `POST /pacientes/cadastro-unico/confirmar-vinculo` responde `204`. Após a confirmação, o paciente pode ser encontrado pela consulta autorizada da unidade. Navegação direta ao detalhe após vincular exigiria resposta com identificadores locais e teste de isolamento por perfil. A inclusão de sessão adicional funciona com a composição inicialmente vazia e usa o editor de participantes já disponível.
- Os endpoints `GET /agendamentos/{id}/correcoes` e `GET /grupos/sessoes/{id}/correcoes` existem no perfil de teste legado. Para mostrar auditoria operacional por atendimento ou por sessão, definir o DTO e publicar uma consulta escopada, com testes de autoria, 404 externo, campos minimizados e paginação. O Pacote 3 pode avançar com as trilhas de resolução e origem que já têm contratos operacionais.
- A matriz de permissões do backend permanece a referência. Regras temporais do servidor, indisponibilidade de agenda, elegibilidade histórica e colisões são confirmadas pela API. A validação integrada deve cobrir recepção na reserva, profissional responsável na correção e administrador dentro da unidade.
- Nas Etapas 8.6 e 8.7, reaproveitar decisões e paginação da aba de Relatórios. Solicitação, decisão, aplicação, auditoria, reabertura e reversão precisam de jornadas distintas. A autoria inconclusiva continua visível e protegida. É necessário aprovar critério institucional para regularização sem prova suficiente e definir a política de reservas futuras após encerramento, antes de qualquer automação relacionada. [Parecer da Etapa 6](etapa6-parecer-encerramento.md)

## Parecer técnico

Pacote 2 implementado no escopo dos contratos já publicados, com validação direcionada aprovada. A regressão integral fica aguardando validação automatizada no commit de entrega. As Etapas 8.6 e 8.7 seguem pendentes. [Workflow da branch](https://github.com/LizReis/SIBRAPS-testagem/actions/workflows/cadastro-unico-diagnostico.yml?query=branch%3Acorrecao-cadastro-unico-isolamento)
