# Etapa 6.3 — contrato de análise do legado clínico inconclusivo

**Referência:** checkpoint `etapa6-3-checkpoint-conciliacao-historicos-legados.md`  
**Branch:** `correcao-cadastro-unico-isolamento`  
**Último código validado:** `1277c5483f0b358b74659fcd3e7c4f0f58ea89dd`, Actions #45264281787 (SUCCESS).  
**Classificação:** especificação preparatória e matriz de verificação. **Não é implementação, não autoriza acesso aos registros ou migração.**

## 1. Problema identificado — dois fluxos diferentes

A estrutura V12/V13 atualmente implementada opera para fatos cuja **unidade histórica local já foi comprovada** pelo servidor. O verificador `VerificacaoFatoRevisaoProvenienciaService` recusa um fato com `unidade_origem_chave` ausente, divergente ou com vínculos de origem inconsistentes. Esse bloqueio é necessário e **não pode ser removido** apenas para abrir tickets do legado.

A tarefa pendente é especificar uma triagem separada para dados **anteriores ao registro de proveniência**, cujos metadados não demonstram a unidade da época. A triagem não concede leitura clínica nem altera atendimento, vínculo, frequência ou indicador.

**Exemplo de risco:** uma pessoa é cadastrada uma só vez e acompanhada pela USF A e pela USF B. Um histórico antigo sem unidade de origem não pertence automaticamente a nenhuma delas. CPF/CNS e lotação atual do responsável não comprovam a unidade que realizou o atendimento.

## 2. Invariantes funcionais confirmadas

- Uma identidade cadastral global por pessoa; vínculos explícitos e independentes por USF específica ou CAPS I, II, AD.
- Compartilhamento limitado ao cadastro autorizado. Prontuário, frequências, grupos, faltas, histórico e indicadores são **da unidade**; não existe acesso cruzado apenas porque a identidade é única.
- Nenhum backfill automático a partir de `capsReferencia`, `usfReferencia`, lotação profissional atual, coordenador atual, endereço, situação territorial ou nome do CAPS.
- Conservação de todos os fatos, evidências, responsáveis históricos, auditorias e versões. Coincidência de horário não comprova duplicidade nem autoriza apagar.
- **Opção B:** solicitação com justificativa por escrito; revisão e validação posterior. Decisão administrativa é evento separado da eventual aplicação clínica, que permanece desativada.
- Registros inconclusivos **não** podem ser tratados como PRESENÇA/FALTA conclusiva, nem alterar automaticamente risco, visita domiciliar, busca ativa ou contadores.
- O fluxo V12/V13 para fatos já comprovados permanece intacto; não misturar tickets sem origem com a fila local existente antes de resolver os direitos de investigação.

## 3. Matriz de classificação diagnóstica (somente leitura)

| Situação | Resultado preparatório | Limite explícito |
|---|---|---|
| Fato novo com origem local íntegra e cadeia coerente | Prosseguir somente pelo fluxo local já protegido | Não criar nova permissão |
| Fato antigo sem unidade de origem | Origem **não comprovada**, requer revisão | Nenhuma unidade é atribuída |
| Histórico com fonte ausente | Revisão da fonte ou caracterização de histórico manual | Não declarar erro automaticamente |
| Evento e fonte com unidades divergentes | Conflito, requer investigação autorizada | Não escolher a unidade atual |
| Remarcação com elo antigo sem origem | Cadeia inconclusiva | Não usar apenas o elo mais recente como prova |
| Remarcação de outra unidade | Fato não acessível pela unidade local | Não expor conteúdo nem existência em listagem local |
| Sessão com responsabilidade histórica ausente | Responsabilidade pendente de comprovação | Não usar coordenador atual |
| Sessão/grupo sem prova de participação do paciente | Integridade inconclusiva | Não imputar frequência |
| Dois fatos com mesmo horário | Possível conferência manual | Não fundir/excluir por coincidência |
| Evidência contraditória ou insuficiente | Permanecer inconclusivo | Sem aprovação inferida |

Essas categorias são **de análise**, não novos enums/estados persistidos nem contratos HTTP. A classificação de evidência do sistema já é diagnóstica: `ClassificadorEvidenciaClinicaLocal` não autoriza mutação.

## 4. Verificação necessária ANTES de permitir qualquer solicitação do legado

Para cada fato candidato, o procedimento futuro deverá validar a identidade e seu vínculo, o tipo de fato e a integridade do registro original, a fonte assistencial, o responsável **na data do evento**, o período, a participação em grupos, as remarcações ancestrais e a unidade demonstrada por evidência contemporânea ao atendimento. Conservar referências auditáveis ao material de prova sem espalhar conteúdo clínico por unidades.

Os procedimentos existentes `CadastroUnicoPreflight` e `OrigemClinicaPreflight` fornecem **contagens agregadas e indícios**, não documentação probatória. Um registro com origem desconhecida não pode ser tratado como pertencente à unidade logada para fins de leitura apenas porque a pessoa tem vínculo nela.

Ao contrário do fluxo V12/V13, **ainda não está definido quem pode inspecionar, pedir evidência e atribuir uma unidade histórica quando nenhuma está comprovada**. Esse é um bloqueio de autorização: não assumir que qualquer profissional, recepcionista ou administrador de USF/CAPS possa examinar conteúdo de outras unidades. A política deverá ser formalmente aprovada antes de qualquer serviço, rota, tela ou acesso ao dado identificável.

## 5. Separação das fases propostas

1. **Inventário sem identificação:** contagens e categorias de risco, backups verificáveis, comparação de schema V8–V13 e avaliação dos registros antigos em ambiente de teste.
2. **Investigação do legado (NÃO IMPLEMENTADA):** somente após política explícita de quem investiga, escopo permitido e evidência admissível. Deve permitir resultado inconclusivo sem acesso cruzado por padrão.
3. **Deliberação auditada (contrato a aprovar):** ator autorizado, justificativa validada, metadados de prova, decisão e data, detecção de duplicidade/concorrência; não presumir que aprovação muda a unidade clínica.
4. **Aplicação clínica separada (NÃO IMPLEMENTADA):** comando distinto, transacional, com rechecagem da prova, integridade e versão, auditoria e reversibilidade. Não executar no mesmo ato de clicar em aprovar.
5. **Migração de indicadores e corte coordenado (NÃO IMPLEMENTADOS):** apenas depois de reconciliação, testes em banco restaurado e comparação de fatos, frequências e auditorias, incluindo RF22/RNF17, busca ativa e relatórios.

## 6. Matriz mínima de testes de segurança da futura implementação

- Mesmo CPF/CNS com dois vínculos em **USFs diferentes** e em **CAPS AD/I/II**: os fatos e decisões de uma unidade não aparecem em outra.
- Acesso direto por UUID/ID a fato de origem incerta ou de outra unidade não retorna conteúdo ou indícios de existência indevidos.
- Usuário não autenticado, profissional, recepção, administrador local e eventual revisor especializado: aplicar exatamente a **matriz de permissão que vier a ser aprovada**, não inventar liberação.
- Caso sem origem, fonte ausente, fonte de paciente divergente, responsabilidade incerta, remarcação cruzada e história manual: preservar inconclusivo até evidência suficiente.
- Duas revisões simultâneas não fabricam duas decisões ou duas aplicações. Decisão rejeitada/inconclusiva não modifica frequência nem indicadores.
- Testes de inserção/alteração com backup restaurável: o número de identidades, fatos, auditorias e versões deve ser reconciliado, sem alterações silenciosas.
- Exportações/relatórios, ordenação, filtros, painel e APIs devem compartilhar a mesma autorização server-side, nunca usar filtro exclusivamente no frontend.
- Reconciliação interrompida ou falha: nenhuma correção parcial, com plano de abortar/reverter testado antes de qualquer corte.

## 7. Decisões ainda necessárias — NÃO tomar por padrão

| Tema | Questão a resolver com equipe/preceptoras | Situação |
|---|---|---|
| Quem investiga a origem `NULL`? | Qual papel e qual escopo podem analisar prova de unidade histórica desconhecida sem expor prontuário de outra unidade? | **Não definido** |
| O que vale como prova? | Documentos/registro de atendimento, autoria, momento, vinculação à unidade, divergência entre provas e guarda | **Não definido** |
| Fatos impossíveis de atribuir | Como registrar análise insuficiente e encerramento ou reabertura sem forçar unidade? | **Não definido** |
| Aprovação versus correção | Critério e responsabilidade para comando clínico separado após deliberação | **Somente separação confirmada; execução não definida** |
| Aplicação dos indicadores por vínculo | Critérios transacionais de recontagem, preservação do legado, reconciliação da última presença/risco/status | **Não implementado** |

## 8. Critérios para avançar de especificação para código

Exigir: acordo funcional sobre as três primeiras questões, política de acesso revisada, distinção inequívoca entre investigação, decisão e aplicação, casos sintéticos reproduzíveis e plano de testes de vazamento entre unidades. Até lá, a rota segura é **continuar apenas a análise, a matriz de segurança e os diagnósticos read-only existentes**.

**Entrega deste documento:** somente especificação, sem alteração de banco, migrations, endpoints, enum persistido, serviços clínicos, frontend, prontuários nem autorização operacional. Nenhum ticket de legado foi criado.
