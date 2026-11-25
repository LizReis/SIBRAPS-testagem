# SIBRAPS — Pacote 6B.2 · Consumidores e frontend por vínculo

**APROVADA PARA REVISÃO** no ambiente experimental.

O pacote parte de `bdff4f5f39ceb2af39241790180638f94aa7e206` na branch `correcao-cadastro-unico-isolamento`. O GitHub Actions validou essa base na execução [45515994232](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45515994232). O SHA final é o do commit que contém este relatório. Em uma cópia da branch, consulte `git log -1 --format=%H -- docs/etapa6-3-pacote6b2-integracao-consumidores-frontend.md`.

## Permissões e dados

Administrador, profissional e recepção podem cadastrar, conferir, vincular e editar o cadastro compartilhado pelo contrato experimental. O JWT determina a unidade e o editor precisa de um vínculo explícito com ela. A criação e a vinculação reutilizam a inicialização existente, com modalidade escolhida explicitamente e referência opcional validada na unidade. Um vínculo já existente conserva sua configuração. O caminho interno de inicialização cadastral admite a recepção. Configuração clínica posterior, frequência, estado e correções continuam sujeitos às permissões de cada comando.

O formulário de edição opera somente com dados demográficos e territoriais. O backend valida CPF/CNS, similaridade, confirmação humana, endereço e versão cadastral. A edição não aplica os campos de modalidade, referência clínica, risco, estado ou frequência. A conferência documental usa POST, devolve apenas nome, nome da mãe e nascimento e instrui o cliente a evitar cache.

A versão do cadastro protege a edição compartilhada. A versão do vínculo protege os comandos de estado e revisão e as ações auditadas de busca ativa. A versão do atendimento ou da sessão protege as mutações assistenciais aplicáveis. As telas repassam a versão recebida no carregamento. A ficha oferece configuração, inativação e reativação do vínculo para a administração. O operador recebe a resposta de concorrência e pode conferir os dados atualizados antes de uma nova tentativa.

## Rotas, serviços e telas

As rotas clínicas experimentais têm prefixo `/api/ensaio-escritores`. O perfil do JWT e a autorização interna dos serviços determinam o acesso.

| Fluxo | Contrato HTTP experimental | Serviços e componentes |
| --- | --- | --- |
| Identidade e cadastro | `POST /pacientes`, `POST /pacientes/cadastro-unico/{conferencia,confirmar-vinculo}`, `PUT /pacientes/{paciente}/vinculos/{vinculo}/cadastro` | `CadastroAcompanhamentoLocalEnsaioService`, `InicioAcompanhamentoLocalEnsaioService`, `ConsultaCadastralExataService`, `CadastroPaciente`, `CadastroExistente`. |
| Lista, ficha e prontuário | `GET /pacientes/pesquisa`, `GET /pacientes/{id}/{detalhe,prontuario,agendamentos}` | `ConsultasClinicasLocaisEnsaioService` e projeção de fatos. `Pacientes`, `DetalhePaciente`, `HistoricoPaciente`, `AgendamentosPaciente`. Dados sem evidência continuam inconclusivos. |
| Agenda e atendimentos | `GET /agendamentos/{id}`, `/agenda`, `/agenda/{data}`, `/vagas`, `/horarios-disponiveis` e `/{id}/remarcacao`, além dos comandos 6B.1 | `DtosAssistenciaisLocaisEnsaioService`, guardas operacionais de oferta, `Agenda` e `NovoAgendamento`. A disponibilidade reutiliza a política de ocupação do profissional para prevenir conflito de horários. A consulta clínica continua restrita à origem e ao vínculo locais. |
| Grupos e sessões | `GET /grupos`, `/{grupo}`, `/sessoes`, `/{grupo}/participantes`, `/proxima-data-sugerida` e `/sessoes-para-inscricao-retroativa` por grupo | `DtosAssistenciaisLocaisEnsaioService`, `SerieGruposLocaisEnsaioService`, `GruposTerapeuticos`, `AgendarGrupoTerapeutico` e `DetalheGrupoTerapeutico`. Reutiliza confirmação, inscrições e correção justificada. |
| Painel, busca ativa e RNF17 | `GET /painel/resumo`, `GET /inicio/resumo`, `GET /pacientes/busca-ativa`, `POST` de visualização e encaminhamento no vínculo, `GET /auditoria/busca-ativa` | `BuscaAtivaPainelLocalEnsaioService`, `HistoricoRiscoVinculoEnsaioService` e `AuditoriaBuscaLocalEnsaioService`. `Inicio`, `Painel` e `BuscaAtivaLocal`. Chave de requisição preserva deduplicação em nova tentativa. Encaminhamento registra a trilha local. |
| Relatórios e pendências | `GET /relatorios/{busca-ativa,frequencia-grupos}`, respectivos `/pdf` e `/excel`, `GET /relatorios/pendencias` e comando de decisão no vínculo | `RelatoriosLocaisEnsaioService`, `PainelPendenciasLocalEnsaioService` e `RevisaoProvenienciaLocalEnsaioService`. `Relatorios` mostra prévia filtrada e aba administrativa com paginação, detalhes e justificativas de solicitação e decisão. Aprovação registra apenas a decisão da Opção B. |

`ConsultasHttpIsoladas`, `CadastroCompartilhadoHttp`, `EscritoresHttpIsolados` e `AplicacaoEnsaioLocal` estão no código de teste. Os serviços experimentais de cadastro, inicialização, consulta, projeção assistencial e pendências receberam as extensões necessárias para autorização, leitura e versões. Os serviços Angular `paciente`, `cadastro-unico`, `agendamento`, `grupo-terapeutico`, `painel`, `inicio` e `relatorio` consomem esses contratos na configuração `ensaio`. Os componentes existentes mantêm a navegação. `contrato-clinico-local.ts` preserva os valores anuláveis e os rótulos de revisão.

## Isolamento e execução de ensaio

A configuração `ensaio` do Angular substitui a raiz clínica em tempo de build. O build padrão mantém a raiz operacional. As chamadas de login, metadados de conta e território e configuração de disponibilidade autorizada usam as rotas auxiliares específicas. Os controladores locais exigem importação explícita, os perfis `test`, `ensaio-estado-local` e `escritores-http-local`, ausência de `dev` e banco H2 dedicado em memória. O servidor de ensaio escuta em `127.0.0.1:8080`. Os caminhos clínicos globais e os escritores legados incompatíveis ficam bloqueados. A inicialização clínica global, a sincronização de USFs, os agendadores e o envio real ficam desativados no ensaio.

Para executar com Java 21 e Node 22, defina `SIBRAPS_ENSAIO_SENHA` com uma senha sintética de pelo menos 16 caracteres antes de iniciar o backend. Em `backend/buscaativa`, rode:

```sh
bash mvnw test-compile spring-boot:test-run -Dspring-boot.run.main-class=br.sibraps.ensaiohttp.AplicacaoEnsaioLocal
```

Em `frontend/sentinela-pet`, com as dependências instaladas, rode:

```sh
npm start -- --configuration ensaio --host 127.0.0.1
```

A execução cria duas USFs sintéticas. Os logins são `administrador.a@test.local`, `profissional.a@test.local` e `recepcao.a@test.local`, com equivalentes `.b` para a segunda USF, usando a senha sintética informada. A base é volátil.

## Verificação

| Verificação | Resultado |
| --- | --- |
| Cadastro e persistência direcionados | 57 testes aprovados na integração inicial. |
| HTTP com JWT real | 6 cenários aprovados, incluindo edição pelos três perfis, âmbito clínico entre duas USFs, agenda, correções e rollback, prontuário, grupos, PDF/Excel, permissão de auditoria, deduplicação e decisão sem alteração clínica. |
| Maven `clean verify` e matriz adicional | 1.384 testes aprovados com `TZ=UTC`, mais 12 cenários adicionais de concorrência. |
| Angular dirigido, configuração operacional e experimental | 49 testes dirigidos no build padrão e 4 na configuração de ensaio. |
| Builds Angular padrão e de ensaio | Aprovados. O build padrão mantém o aviso de orçamento inicial de 500 kB. |
| Isolamento do artefato e execução local | Controladores e servidor de ensaio ausentes do JAR, e prefixo experimental ausente do JavaScript operacional. Servidor local validado com duas USFs, login real, rejeição de chamada sem token e bloqueio da pesquisa clínica legada. |

## Limites e bloqueios para o corte

O painel mostra evidências locais e distingue períodos históricos indisponíveis, alertas inconclusivos, pendências e indicadores sem confirmação. O prontuário mantém comprovação de origem e a busca ativa preserva RF22, os pacientes vermelhos elegíveis independentemente do gatilho e as mensagens específicas para situação de rua. O cabeçalho do ensaio não apresenta um contador de notificações operacionais.

O processo de análise de fato de origem incerta usa a solicitação explícita do 6B.1 e a fila administrativa do 6B.2. A aprovação não reatribui origem, não corrige frequência e não encerra a pendência clínica automaticamente. A saída CSV legada é apenas um controle visual sem exportação. O ensaio oferece os formatos PDF e Excel.

A ativação operacional depende do 6B.3 e de validação de implantação. Ficam reservados:

- Unicidade persistente de CPF/CNS normalizados e concorrência entre instâncias, incluindo tratamento seguro de duplicidades antigas.
- Marco inicial confiável e comprovação da cobertura mensal. A migration V16 segue preservada.
- Substituição coordenada de escritores e consumidores globais com ativação do monitoramento local.
- Resolução da corrida entre revalidação e envio de notificações e tratamento autorizado de reservas interrompidas ou incertas.
- Desativação do mecanismo legado de notificações durante a transição. O envio real do novo mecanismo depende da aprovação de segurança.

A entrega fica na branch de trabalho, sem corte operacional, envio de e-mails reais, alteração de migrations ou da `main`.
