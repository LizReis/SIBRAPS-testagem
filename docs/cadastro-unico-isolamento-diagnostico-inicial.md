# Regularização do cadastro único e isolamento assistencial — diagnóstico inicial

**Branch:** `correcao-cadastro-unico-isolamento`, derivada de `correcao-fluxos-assistenciais-sibraps`. **Escopo:** subetapa independente anterior à 6.3; não alterar a `main`.

## Decisões funcionais confirmadas

1. Uma pessoa tem apenas **uma identidade cadastral** (CPF/CNS únicos) em todo o SIBRAPS. Dados pessoais cadastrais podem ser consultados/atualizados por usuários autorizados de unidades com finalidade legítima; alterações auditadas. Busca controlada do cadastro por CPF/CNS exatos; não liberar listagem global.
2. Cada USF específica e cada CAPS AD/I/II possui **vínculo assistencial próprio** com a identidade, criado explicitamente; a referência `capsReferencia` / USF territorial não cria vínculo clínico. A mesma pessoa pode ter vínculos simultâneos; alta em um vínculo não encerra outro.
3. Cada unidade mantém **seus** agendamentos, sessões/grupos, presenças/faltas, histórico, risco, alertas de busca ativa, responsável, estado e pendências. Sem acesso, consulta, atualização ou contagem de dados clínicos de outras unidades. A contagem clínica unificada *entre modalidades* mantém-se, mas exclusivamente dentro do **mesmo vínculo/unidade**. Nenhuma combinação entre USF e CAPS.
4. USF cadastrante mantém restrição à **própria USF**; CAPS também pode criar identidade e vínculo da própria unidade. Profissional, administrador e recepção podem **cadastrar** conforme permissões já existentes no fluxo; conceder `PROFISSIONAL` no POST sem ampliar edição, inativação, reativação nem autorizações clínicas. A alteração de *cadastro* do profissional nesta etapa não deve ser confundida com poder modificar atendimentos de terceiros.
5. USF e CAPS de referência podem estar não informados/não possuir referência (significados diferentes); não forçar referência fictícia. Identidade existente será reutilizada mediante correspondência segura de CPF/CNS; documento conflitante requer revisão protegida. Não revelar histórico ou vinculações de terceiros.
6. Mudança entre USFs conserva a mesma identidade, guarda histórico do vínculo anterior, inicia vínculo independente novo com operação autorizada/auditada; não transfere automaticamente o histórico.
7. Preservar auditorias clínicas, regras RF22/RNF17, P01–P05, 6.2A–G, histórico e responsabilidade assistencial. Não promover migrations históricas V1–V7; novas mudanças em migration aditiva com rollback/testes, e nunca atribuir origem clínica incerta sem evidência.

## Evidências no código anterior à correção

- `Paciente` mistura identidade e estado assistencial: `countFaltas`, `dataUltimaPresenca`, `classificacaoRisco`, `statusPaciente`, `tipoAcompanhamento`, `gatilhoVisitaAcionado`, `indicadoresPendentes`, `profissionalReferencia`, `usfReferencia`, `capsReferencia`.
- `EscopoUnidadeService.specificationPaciente`, `pacientePertenceAoEscopo`, `criterioPacienteAuditoria`: USF por `usfReferencia`; CAPS por `capsReferencia`. Isso faz a referência conceder visibilidade clínica, contra o requisito de vínculo explícito.
- `PacienteServiceImpl.save`: CPF/CNS são verificados globalmente e `validarAcesso` rejeita paciente já existente fora do escopo. Isso impede o aproveitamento da identidade em outra unidade. O preenchimento de USF alheia já é recusado no backend e deve continuar sendo.
- `HistoricoPacienteServiceImpl.consultar`: consulta `findByPacienteIdPublicoOrderByOcorridoEmDesc` sem filtro de unidade **após verificar só o acesso ao paciente**. Não é adequado quando o mesmo cadastro passar a ter múltiplos vínculos.
- `PacienteServiceImpl.recalcularAssiduidadePaciente`: reúne agendamentos e grupos de toda a identidade, e grava risco/contagem no `Paciente`; não atende separação por unidade.
- `PacienteListaDTO` e relatórios expõem projeções assistenciais do cadastro comum; precisam passar a projetar dados do vínculo.
- `IsolamentoUnidadeTests` (especialmente `unidadeDeterminaPacientesSemUsarProfissionalDeReferencia` e resumos de CAPS) formalizam o modelo antigo de acesso por referência. Não mascarar: novos testes devem identificar a divergência e os antigos só podem ser atualizados quando houver novo escopo implementado e demonstrado.
- `PacienteController` tinha POST autorizado somente para admin/recepção; Angular `podeCadastrarPaciente` e rota `pacientes/novo` bloqueavam profissional. Permissão específica ampliada nesta branch, mantendo PUT restrito.

## Reproduções e testes planejados (não declarar aprovados antes de Actions)

- Cadastro por PROFISSIONAL CAPS e USF de própria unidade => 201, registro recuperável; PUT pelo profissional => 403; POST fingindo USF alheia => 404 e nenhuma gravação.
- Identidade cadastrada por USF com `capsReferencia` informada => aparece na USF; **não** aparece no CAPS antes do vínculo explícito.
- CAPS vincula identidade existente por CPF/CNS exatos => não duplica pessoa; somente novo vínculo local; consultas e grupos locais podem operar.
- Paciente acompanhado em USF e CAPS com históricos divergentes: frequências, última presença, faltas, indicadores, busca ativa, encerrar/reativar, relatórios e histórico sempre locais, sem vazamentos por UUID, CPF/CNS, filtros, PDF/Excel, logs ou painéis.
- Mudança entre duas USFs: preservar histórico original e ausência de acesso cruzado; referência territorial diferente de vínculo e de transferência.
- Migrations sobre banco legado com registros potencialmente ambíguos: não escolher automaticamente unidade de um fato; preservar e sinalizar até revisão. Constraints de unicidade e concorrência para identidade/vínculo.
- Cadastro único em caso de CPF e CNS apontarem a identidades diferentes: não mesclar automaticamente, impedir operação e preservar dados.
- Testes de segurança: não autenticado 401; ação sem perfil 403; fora do escopo 404 conforme convenção atual; usuários sem unidade configurada falham fechados.

## Execução por etapas

**A — diagnóstico e reprodução:** inventário de entidades, consultas, rotas, contratos, testes de isolamento e migração; incluir cobertura da permissão PROFISSIONAL (primeira entrega desta branch).

**B — modelagem + migração:** introduzir identidade global e vínculo assistencial local sem mudar histórico; selecionar estratégia de migração aditiva e tratar registros ambíguos antes de codificar.

**C — backend:** filtros por vínculo, cadastramento/reuso de identidade, correções de indicadores/históricos, auditorias e transações.

**D — frontend:** buscas controladas, fluxos novo paciente/vincular existente, referências opcionais e listas locais.

**E — validação:** Maven verify + concorrência opt-in, build Angular, testes específicos e regressões; registrar falhas Angular antigas em separado, sem ocultá-las.

**Proibições:** nenhuma mudança clínica presumida, limpeza de dados antigos, bypass de escopo, vinculação automática por CAPS de referência, merge para main ou declaração de CI verde sem evidência.
