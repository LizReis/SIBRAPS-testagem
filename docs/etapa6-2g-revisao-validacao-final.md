# SIBRAPS — Etapa 6.2G: revisão, validação integral e encerramento técnico condicionado

**Branch:** `correcao-fluxos-assistenciais-sibraps`  
**Escopo:** fechamento da prevenção de novos conflitos da 6.2A–F; NÃO inclui reconciliação automática do legado (6.3–6.6), mudanças no Angular (7–9) ou integração à `main`.  
**Verificação final:** commit `1072de12d40dc172e4881c8395ca35de60bdd151`; GitHub Actions [44434930803](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/44434930803).

## 1. Portões verificados

| Portão | Resultado observado | Parecer |
|---|---|---|
| Backend `bash ./mvnw -B -ntp verify`, Java 21 | **1019 testes; 0 falhas, 0 erros, 0 ignorados; BUILD SUCCESS** | Aprovado em H2 de teste; build/empacotamento Maven aprovado |
| `ReconciliationConcurrency61Characterization` opt-in | **12 testes; 0 falhas, 0 erros; BUILD SUCCESS** | Aprovado; matriz original de concorrência revalidada |
| Build de produção Angular | **Sucesso** | Compilação aprovada; aviso de orçamento inicial 847,77 kB frente a 500 kB |
| Suíte diagnóstica Angular | **97/104 aprovados; 7 falhas; 5 erros não tratados; 6 arquivos de testes com falha, 22 aprovados** | PENDENTE (Q01–Q04 / etapas 7–9), sem regressão nova de frontend atribuível à 6.2 |
| Estado global do workflow | **failure** | Falha exclusivamente no job diagnóstico Angular; não registrar o workflow como verde |
| `main` e banco de implantação | Não alterados/não validados aqui | Fora do escopo; não há merge nem certificação de produção |

Os **1019** são a contagem da suíte padrão na execução integral; os **12** opt-in foram executados em comando separado, não somados como 1031 testes distintos. Resultado anterior 6.2F3B: [44432804813](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/44432804813), 7/7 cenários e 229/229 regressões direcionadas aprovados. Outros portões F1, F2 e F3A tiveram aprovação específica documentada em seus relatórios. Registros íntegros e artefatos de relatório são fornecidos pelo GitHub Actions, com retenção configurada de sete dias.

## 2. Regressão histórica descoberta e tratamento

A primeira execução integral G, [44434238209](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/44434238209), teve **1018 testes, 1 falha**, na classe `GroupResponsibilityMigrationTests`; Angular build aprovado e Angular diagnóstico com as mesmas falhas anteriores. O teste legado esperava HTTP 200 ao corrigir frequência coletiva de uma sessão migrada, mas recebeu HTTP 409 `HISTORICO_ASSISTENCIAL_PENDENTE_RECONCILIACAO`.

A análise identificou divergência **no fixture V4 sintético**: o histórico de frequência aponta `profissional_id=1` (administrador), enquanto a responsabilidade histórica comprovada da sessão migrada aponta `responsavel_atendimento_id=2` (profissional A). A verificação mais estrita introduzida na 6.2D1/D2 não deve ser relaxada para permitir uma alteração arbitrária do histórico.

No commit `1072de1`, foi modificada **somente a classe de testes**:
1. Contraprova nova exige HTTP 409, código de pendência e snapshot integral sem mudança quando a evidência do fixture permanece contraditória.
2. O teste positivo de autorização, correção, auditoria, reinicialização e backup/restauração usa **variante sintética coerente**, com autor do evento clínico igual ao responsável comprovado; o fixture original do repositório não foi sobrescrito.
3. Nenhum serviço, DTO, entidade, migração, política clínica ou controle de acesso de produção foi alterado.

Na segunda execução G, os **9/9** casos de `GroupResponsibilityMigrationTests` foram aprovados dentro dos 1019 da suíte geral.

## 3. Cobertura e delimitação da 6.2

As entregas já implementadas e caracterizadas compreendem:
- 6.2A–B: infraestrutura de locks/consultas de conflito; reservas e remarcações individuais.
- 6.2C1–C2: criação de grupo/sessões, inscrições, transferência de coordenação, regeneração de séries e conflitos entre paciente/profissional/horário.
- 6.2D1–D2: frequência factual individual e coletiva, histórico coerente, recusa de fatos contraditórios, auditoria e autorização preservadas.
- 6.2E: integridade de novas cadeias de remarcação e bloqueio de bypass legado.
- 6.2F1–F3B: concorrência real com transações independentes, ordens de commit, rollback, escopo e privacidade, mais regressões dirigidas.
- 6.2G: execução `verify` integral backend, matriz opt-in e diagnóstico/build do Angular; formalização das limitações.

Aprovação dos testes **não é** afirmação de que todo histórico pré-existente está reconciliado nem que todos os interleavings/bancos foram provados. H2 sintético não certifica SGBD de implantação, restauração real, desempenho ou concorrência sob tráfego real. Não foram feitos testes de usabilidade/end-to-end com profissionais nesta etapa. Duração assistencial não foi presumida de grade de 30 minutos. Não se alterou contagem unificada, limiares de risco, RF22, RNF17, responsabilidade histórica incerta, política de mudanças de perfil sobre compromissos existentes ou dados clínicos persistidos.

## 4. Pendências explicitamente fora do fechamento de 6.2

1. **6.3:** infraestrutura persistente para **pendências clínicas**, com identificação, consulta autorizada, evidências, versão/idempotência e encaminhamento; abrir pendência sem mudar fato, frequência, risco, reserva ou indicador. Matriz: responsável e administrador da própria unidade autorizados conforme ação; recepção não; outra unidade invisível (404); não autenticado (401).
2. **6.4:** decisão autorizada sobre duplicidade/contradição com vínculo entre fatos e auditoria imutável; jamais excluir fato original nem deduplicar apenas por data/hora.
3. **6.5:** frequência histórica desconhecida, responsabilidade sem prova, cadeia temporal/legado e proveniência; sem inferir autor ou presença.
4. **6.6:** contagem unificada, presença, risco, visita RF22, RNF17, consumidores/relatórios, migrações aditivas e validação/retestes integrais conforme plano.
5. **7–9:** falhas Angular diagnósticas (7 falhas, 5 erros), configuração/testes de frontend e CI, orçamento do bundle, resposta HTTP global, dependências, banco definitivo/backup/segurança operacional.

A decisão sobre **quais evidências mínimas são aceitas, política de retenção e acesso a anexos** ainda requer definição antes de implantar armazenamento de documentos sensíveis na 6.3. Não promover a 6.2 para `main`, não ocultar falhas Angular usando `continue-on-error`, não inventar resultados clínicos e não modificar migrações V1–V7 nesta formalização.

## 5. Parecer de encerramento e próximo portão

**6.2G documentada: portão de backend concluído com sucesso (1019/1019 + 12/12 opt-in).** O estado global do workflow permanece vermelho **pelo diagnóstico Angular legado**; não registrar CI integral verde. A candidata 6.2 está pronta para **iniciar o planejamento técnico da 6.3**, não para declarar deploy/integração geral do sistema. A 6.3 deve começar por inventário e testes de caracterização das pendências e dos contratos já existentes, com alterações dirigidas, transações reais, autorização e rollback; qualquer política de evidências não aprovada deve permanecer como decisão aberta.
