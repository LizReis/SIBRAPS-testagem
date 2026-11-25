# SIBRAPS — Pacote 6B.1 · Integração dos escritores do backend

**APROVADA PARA REVISÃO** no escopo do ensaio isolado.

A entrega parte de `ba9dedbb053dea2e1378c700e1f34ee9f68a0bcb`, validado pela execução [45510605204](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45510605204). O commit final é o que contém este relatório na branch `correcao-cadastro-unico-isolamento`. Para obter o SHA do próprio commit em um clone dessa branch, execute `git log -1 --format=%H -- docs/etapa6-3-pacote6b1-integracao-escritores-backend.md`.

## Escritores e contratos

As rotas da tabela têm o prefixo `/api/ensaio-escritores` e são registradas por importação explícita de `EscritoresHttpIsolados`. A abreviação `{local}` representa `/pacientes/{paciente}/vinculos/{vinculo}`. **A** indica administrador, **P** profissional e **R** recepção. O JWT estabelece a unidade. A coluna de perfis indica a permissão inicial. O serviço ainda revalida escopo, titularidade histórica e demais condições da operação.

| Escritor | Método e rota do ensaio | Serviço e perfis |
| --- | --- | --- |
| Criar identidade e iniciar acompanhamento | `POST /pacientes` | `CadastroAcompanhamentoLocalEnsaioService`, `PacienteServiceImpl` e `InicioAcompanhamentoLocalEnsaioService`. A/P. Retorna IDs e versões. |
| Confirmar identidade e vincular explicitamente | `POST /pacientes/cadastro-unico/confirmar-vinculo` | `ConfirmacaoVinculacaoIdentidadeService`, `VinculacaoIdentidadeExistenteService` e inicialização local. A/P. Mantém 204 sem corpo e `Cache-Control: no-store`. |
| Editar cadastro compartilhado | `PUT {local}/cadastro` | `CadastroAcompanhamentoLocalEnsaioService` e `PacienteServiceImpl`. A/R. Exige versão da identidade e vínculo autorizado, ignora campos clínicos globais do DTO e rejeita contador informado. |
| Configurar modalidade e referência profissional | `PATCH {local}/acompanhamento` | `ComandosEstadoLocalEnsaioService` e `EstadoClinicoLocalEnsaioService`. A. O endpoint separa configuração clínica local da edição cadastral legada. |
| Encerrar, reativar e inativar | `PATCH {local}/encerrar`, `PATCH {local}/reativar`, `DELETE {local}` | Mesmos serviços de estado local. Encerrar A/P com verificação de referência. Reativar e inativar A. As combinações de óbito seguem fora do comando local. |
| Criar ou remarcar atendimento | `POST /agendamentos`, com `agendamentoOriginalId` na remarcação | `ReservaIndividualLocalEnsaioService` e `FrequenciaIndividualLocalEnsaioService`. A/R, em linha com a rota legada. Mantém a cadeia, a origem e a validação de vaga. |
| Registrar primeira frequência ou cancelar | `PATCH /agendamentos/{id}/status` | `ComandosAssistenciaisLocaisEnsaioService` e `FrequenciaIndividualLocalEnsaioService`. A/P/R. Recepção pode registrar a primeira frequência e executar operações de reserva permitidas. Frequência factual já lançada fica sujeita à autorização de correção. |
| Corrigir, recuperar reserva e regularizar frequência de cancelamento | `PATCH /agendamentos/{id}/frequencia/correcao`, `POST /agendamentos/{id}/regularizacao-cancelamento/{recuperar-reserva ou frequencia}` | Mesmos serviços individuais. A/P, com justificativa, janela aplicável e responsabilidade histórica. |
| Criar e editar série, abrir sessão, incluir e remover participante da sessão | `POST /grupos`, `PUT /grupos/{grupo}`, `POST /grupos/sessoes`, `POST /grupos/sessoes/{sessao}/participantes`, `DELETE /grupos/sessoes/{sessao}/participantes/{paciente}` | `SerieGruposLocaisEnsaioService` e `FrequenciaColetivaLocalEnsaioService`. A/P/R com guardas operacionais do serviço. |
| Presença provisória, ocorrência, correção e conferência de status | Em `/grupos/sessoes/{sessao}`, `PATCH /participantes/{paciente}/presenca`, `POST /confirmacao-ocorrencia`, `PATCH /frequencias`, `PATCH /status` | `FrequenciaColetivaLocalEnsaioService` e `ComandosAssistenciaisLocaisEnsaioService`. A/P. A confirmação registra ocorrência ou cancelamento. O PATCH de status só confere AGENDADA, sem gravar. |
| Inscrições futura e retroativa, remoção das futuras | Em `/grupos/{grupo}`, `POST /inscricoes-futuras`, `POST /inscricoes-retroativas`, `DELETE /participantes/{paciente}` | `SerieGruposLocaisEnsaioService`. A/P. Valida escopo, responsabilidade, elegibilidade e data de cada sessão. |
| Histórico manual | `POST {local}/historico` | `HistoricoManualLocalEnsaioService`. A/P. Grava evento local, preservando a distinção entre registro manual e fato de frequência. |
| Solicitação e decisão da Opção B | `POST {local}/revisoes`, `POST {local}/revisoes/{ticket}/decisao` | `RevisaoProvenienciaLocalEnsaioService` envolve os serviços V12/V13. Solicitação A/P, decisão A. A decisão registra deliberação. Uma eventual correção tem comando e autorização próprios. |

`EscritoresHttpIsolados.java` e `EscritoresLocaisHttpTests.java` estão exclusivamente em `src/test/java`. A criação e a edição cadastrais alteraram `PacienteMapper`, `PacienteServiceImpl`, `CadastroAcompanhamentoLocalEnsaioService` e `VinculoAssistencialRepository`, com as travas documentais em `DocumentosCadastroLocalLocks`. `FrequenciaIndividualLocalEnsaioService` passou a distinguir a primeira frequência operacional da correção. `FrequenciaColetivaLocalEnsaioService` passou a atender o PATCH de conferência legado com a mesma coordenação local. `AgendamentoServiceImpl`, `GrupoTerapeuticoServiceImpl` e `HistoricoPacienteServiceImpl` foram comparados e continuam atendendo os chamadores legados. Os adaptadores reaproveitam os DTOs operacionais e `DtosAssistenciaisLocaisEnsaioService`.

### Compatibilidade que precisa de decisão antes do corte

- O cadastro e a confirmação existentes permitem recepção. O ensaio combina essas operações com inicialização clínica e conserva a autorização clínica A/P dos serviços locais. Definir como a recepção fará cadastro e vinculação sem inicializar acompanhamento clínico. A edição demográfica por A/R segue disponível no ensaio.
- A edição da agenda existente opera por status ou remarcação via POST. Em caso de cancelamento incorreto, o ensaio exige a recuperação ou regularização dedicada e sua justificativa. Validar o tratamento dos clientes legados que enviam `AGENDADO` pelo PATCH genérico no corte.
- Diversos contratos legados aceitam versão ausente. O ensaio requer versão explícita nos comandos de mutação já versionados. Criação e comandos de série sem versão no DTO preservam seus contratos e revalidação sob trava. Ajustar os clientes antes de redirecionar rotas. Respostas minimizadas de cadastro e de série são específicas do ensaio.
- Validar a política de transferência ou desligamento de profissionais com referência vinculada, inclusive vínculos encerrados, além da política transversal de óbito. O ensaio mantém as guardas existentes.

## Isolamento, atomicidade e concorrência

A configuração experimental exige `test`, `ensaio-estado-local` e `escritores-http-local`, com `dev` ausente. Valida a URL do H2 dedicado em memória antes da inicialização da persistência e verifica os metadados da conexão. O interceptador admite os três controladores de ensaio e o login real. As demais rotas HTTP, inclusive a leitura legada de busca ativa com efeitos de escrita, ficam fora do ensaio. O contexto também retira o inicializador global, o monitor e o agendador de alertas, a sincronização de USFs e o processador de agendamentos. E-mail está desativado, com verificação de ausência de chamadas no teste HTTP.

A criação da identidade e de seu vínculo, incluindo inicialização V14, confirma em uma transação. O mapper cadastral aplica uma lista explícita de campos compartilhados. Os campos clínicos globais anuláveis da nova identidade ficam vazios. O zero da coluna primitiva legada de faltas é apenas o valor de armazenamento da nova linha. A edição cadastral confere a versão da identidade sob trava de vínculo e preserva a clínica de todas as unidades. As travas de CPF/CNS normalizados são adquiridas em ordem estável antes das travas do paciente e retidas até o fim da transação. Essa garantia documental abrange os caminhos locais no processo do ensaio. A unicidade fora desse contexto ainda exige estratégia persistente que considere duplicidades anteriores e concorrência de múltiplas instâncias.

Os escritores de estado e fatos reaproveitam `CoordenacaoEscritaClinicaEnsaio`, `PlanoCoordenacaoEscritaClinica` e `AgendaAssistencialLocks`. Preservam ordem das travas, revalidação de atores, vínculos, fatos e versões, com frequência, histórico/auditoria e recálculo do vínculo no mesmo commit. Permanecem as regras RF22, a elegibilidade de vermelho para visita independentemente do gatilho e o tratamento de origem desconhecida ou indicador inconclusivo. O teste negativo existente ainda demonstra escrita de frequência entre a verificação de ausência e o commit da inicialização quando o escritor desrespeita a trava da identidade.

## Escritores globais restantes e dependências do corte

| Caminho ainda operacional | Dependência |
| --- | --- |
| `PacienteController` e `PacienteServiceImpl` no cadastro, edição, estado e inativação globais. Confirmação pública de cadastro único e chamada direta de vinculação. | Adotar os contratos locais em conjunto, definir a operação cadastral da recepção e proteger unicidade entre instâncias. |
| `AgendamentoController/ServiceImpl`, `GrupoTerapeuticoController/ServiceImpl` e `HistoricoPacienteServiceImpl`. Inscrições e correções antigas, histórico manual e rotinas internas de recálculo global. | Substituir chamadores de forma coordenada, com paridade de contrato e tratamento explícito de proveniência incerta. |
| `PacienteServiceImpl.recalcularAssiduidadePaciente` e recálculos derivados, `GET /api/pacientes/busca-ativa`, `AgendaMigratedIndicatorsInitializer`, `MonitoramentoBuscaAtiva` e o fluxo legado de alertas globais. | Coordenar a retirada dos caminhos globais com os consumidores e com operação do monitoramento local. |
| Chamadas V12/V13 diretas, gravações por repositórios/DML, fixtures dev, importações e manutenção. Alterações de usuário, disponibilidade ou bloqueio de agenda interferem nas condições dos escritores. | Auditar os pontos de chamada antes de abrir escrita local em operação. Preservar as guardas de lotação e referência e as travas de agenda para operações concorrentes. |

## Verificação

| Verificação | Resultado |
| --- | --- |
| Cadastro e persistência local direcionados | 52 testes aprovados. |
| HTTP experimental | 5 testes, com login e JWT reais, duas USFs, edição e confirmação cadastrais, rollback, disputa documental, concorrência entre edição cadastral, frequência e correção, regularização de cancelamento, grupo, histórico e estado. |
| Maven `clean verify` | 1.383 testes aprovados, sem falhas, erros ou ignorados. Adaptadores HTTP ausentes do JAR. |
| Matriz `ReconciliationConcurrency61Characterization` adicional | 12 testes aprovados, sem falhas, erros ou ignorados. |
| Angular direcionado pelos cinco seletores existentes | 33 aprovados, distribuídos em 4, 5, 8, 11 e 5. |
| Angular `npm run build` | Aprovado. O aviso de orçamento inicial permanece, com 858,72 kB para orçamento de 500 kB. |

A primeira execução integral local foi interrompida ao reproduzir falhas legadas de data na virada do dia em UTC. Alguns testes usam a data local da JVM e um relógio fixo ancorado em UTC. A verificação final foi rodada com `TZ=UTC`, como no CI.

## Pendências 6B.2 e 6B.3

O **6B.2** deve integrar os consumidores clínicos por vínculo, incluindo leitura de agenda e grupos, histórico, busca ativa, painéis e relatórios, com escopo e tratamento de dados inconclusivos. A adequação de contratos e telas seguirá nessa transição.

- **6B.3.** Estabelecer marco confiável de evolução histórica por unidade, sem fabricar informações anteriores, e validar a cobertura histórica.
- Preservar a migration V16, já implementada e validada pelos testes da base.
- Resolver a concorrência entre revalidação e envio de notificações e definir o tratamento autorizado para reservas interrompidas ou incertas.
- Desativar de forma coordenada o mecanismo legado de notificações globais. Manter o novo envio real desativado até aprovação de sua segurança.
- Ativar o monitoramento local em conjunto com a substituição dos escritores e consumidores globais.

O Pacote 6B.1 foi preparado para revisão na branch de trabalho. A aplicação operacional, as notificações reais, as migrations, as telas e a `main` ficaram fora da entrega.
