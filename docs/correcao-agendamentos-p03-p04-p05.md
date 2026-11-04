# Etapa 2 — estabilidade, CI e integridade dos agendamentos

Base: `main`, commit `967d5162dd543ec9005608739128423903e75e30`.
Branch de revisão: `correcao-estabilidade-agenda-p03-p04-p05`.
Não há merge ou deploy automático. P06–P16 e Q01–Q04 permanecem fora do escopo.

## Fase A — configuração e testes

| Pendência | Antes | Depois e validação |
|---|---|---|
| Variáveis externas | Três falhas reproduzidas com terminal configurado em `dev` | `@ActiveProfiles("test")` nas integrações e isolamento de propriedades somente nos runners de configuração; 163 casos passam com e sem variáveis de desenvolvimento |
| Histórico Git | `RetiredJwtFixture` executava `git show` | Removido; política imutável `JwtKeyPolicy`, teste sintético do fingerprint bloqueado no mesmo construtor da aplicação e verificação independente do fingerprint histórico |
| CI | Execução manual | Workflow `SIBRAPS CI`, validado com actionlint; execução remota depende de abertura do PR |
| Logging | Spring Security TRACE por padrão | WARN; depuração DEBUG temporária e explícita descrita em `seguranca-p01-p02.md` |

Não se apaga nem se modifica o ambiente do desenvolvedor. A simulação de ausência
de chave é local ao contexto do teste. A chave de aplicação continua obrigatória.
A política histórica permanece fixa no código de produção; não há propriedade
externa para remover fingerprints revogados ou aceitar a chave anterior.
O teste sintético prova o mecanismo de revogação na inicialização, sem recuperar
ou republicar o material histórico. Não equivale a reassinar tokens históricos.

## P05 — remarcação entre pacientes

**Causa:** o atendimento original era validado por escopo e situação, mas o
paciente persistido não era comparado. A reprodução retornou 201 para pessoa
diferente. Remarcações concorrentes também podiam terminar em erro otimista não
tratado pelo contrato HTTP.

**Correção:** carregar o original com bloqueio pessimista; validar o escopo;
comparar `original.paciente.id` e `novoPaciente.id`; só depois validar situação e
alterar original, novo atendimento e histórico. Pacientes diferentes retornam422.
Original inexistente/fora da unidade retorna404; situação incompatível retorna409.

Preservados: remarcação do mesmo paciente entre profissionais distintos,
remarcação após falta e sinalizador `remarcacaoAposFalta`. A transação engloba
ambos os atendimentos e o histórico; falhas não podem deixar efeitos parciais.
Os testes comparam status/versão, total de atendimentos, histórico, faltas,
última presença, risco e gatilho de visita antes/depois de rejeições.

## P04 — concorrência

**Causa:** apenas o profissional era bloqueado. Profissionais diferentes podiam
consultar simultaneamente a ausência de conflito do mesmo paciente. Antes da
correção, os cinco pares executados gravaram duas marcações; os três grupos de
oito solicitações também confirmaram duas por grupo. Dez casos da bateria
concorrente falharam antes da mudança.

**Correção:** manter o paciente bloqueado com `PESSIMISTIC_WRITE` durante toda a
verificação e gravação. Preservado o bloqueio do profissional e do original.
Ordem única nos caminhos da agenda: **paciente → profissional → atendimento**.

A atualização de status primeiro lê somente IDs escalares, depois adquire os
bloqueios e carrega o atendimento. Isso evita alterar/flushar um atendimento
antes de bloquear seu paciente e evita usar uma entidade de atendimento
carregada antes da espera. Uma reativação de situação não ocupante para situação
ativa também verifica conflito, disponibilidade, bloqueios e capacidade.

Situações que ocupam vaga continuam `AGENDADO`, `REMARCADO` e `PRESENTE`.
`CANCELADO`, `FALTOU` e `REMARCADO_ORIGEM` não ocupam a vaga, conforme política
existente. A restrição prévia a registrar status em datas futuras foi preservada;
os testes de concorrência com mudança de status usam a data atual.

### Tratamento de erros e banco futuro

- Conflito de horário ou capacidade:409. Dados/horário inválidos:422. Fora do escopo:404.
- Falhas traduzidas de bloqueio/concorrência retornam409 com mensagem genérica,
  sem publicar SQL. A transação é revertida; não há repetição automática de escrita.
- Um timeout/deadlock não conta como confirmação funcional. Os testes exigem
  resultados de negócio e o estado persistido correto; exceções inesperadas falham.
- Não foi criada uma unicidade incondicional em paciente/data/hora: ela também
  impediria manter atendimentos cancelados ou substituídos como histórico.
  Uma restrição parcial de situações ativas pode ser defesa adicional em banco
  futuro, mas precisa de projeto/migração específica e verificação dos dados existentes.
- A garantia testada é H2, com as escritas pelos fluxos coordenados da aplicação.
  SQL direto/importações não passam por esses bloqueios. Validar novamente em
  outro banco: isolamento, leitura após espera, tempo de lock, índices, plano de
  consulta, semântica de índices parciais e comportamento sob múltiplas instâncias.
  Não se declara validação de concorrência PostgreSQL/MySQL ou outro banco não testado.

## P03 — grade compartilhada

**Causa:** a consulta gerava uma grade, mas o cadastro só conferia capacidade,
disponibilidade e conflitos. Os oito horários da auditoria foram usados sem
substituição: `00:00`, `03:17`, `06:59`, `11:31`, `12:00`, `18:30`, `23:59`, `08:17`,
todos inválidos para `MANHA`.

`AgendaHorarioProperties.grade(turno)` passa a ser a fonte única para consulta e
validação. Configuração preservada:

| Turno | Início | Fim | Intervalo |
|---|---|---|---|
| MANHA | 07:00 | 11:30 | 30min |
| TARDE | 13:00 | 18:30 | 30min |

O início é válido. O fim é inclusivo quando pertence à grade iniciada no horário
configurado — ambos os finais atuais pertencem. A grade não aceita segundos ou
frações adicionais aos slots. Em configuração customizada, o intervalo é contado
desde o início configurado; não é um múltiplo fixo do relógio. A geração é limitada
pela duração do período e não pode dar volta na meia-noite/entrar em laço infinito.

O cadastro rejeita ausência de campos, incompatibilidade turno/hora e horários
fora da grade. Continuam necessárias disponibilidade, agenda desbloqueada,
capacidade e ausência de conflito. Um slot listado pode ser ocupado por outra
transação após a consulta; o cadastro sempre revalida sob bloqueio.

## Arquivos principais

| Arquivo | Alteração |
|---|---|
| `services/impl/JwtKeyPolicy.java`, `TokenServiceImpl.java` | Separação testável da política JWT, sem flexibilizar produção |
| `src/test/.../TestEnvironmentIsolation.java`, `SecurityTestSupport.java`, testes JWT/H2 | Independência de ambiente/histórico |
| `.github/workflows/ci.yml`, `application.properties` | CI e nível de logging |
| `services/impl/AgendamentoServiceImpl.java` | Identidade, transação, ordem de bloqueio, reativação e grade |
| `repositories/AgendamentoRepository.java` | Leitura escalar de identidade e bloqueio do atendimento |
| `config/AgendaHorarioProperties.java` | Grade única com limites inclusivos |
| `services/exceptions/ResourceExceptionHandler.java` | HTTP409 para conflitos transacionais, sem detalhes internos |
| `AgendaIntegritySupport`, `AgendaRescheduleTests`, `AgendaConcurrencyTests` | Fixtures, rollback e concorrência real |
| `AgendaGridTests`, `AgendaConfiguredGridTests`, `AgendaCombinedTests` | Matriz P03 e integração dos três defeitos |

## Evidências e execução

Fase A:163 testes aprovados nos dois ambientes. Após as correções:231 casos no
backend, sem falhas/erros/ignorados, incluindo execução com variáveis `dev` e ordem
aleatória de classes (seed731). Os testes originais de segurança, USF/CAPS,
pacientes, profissionais, grupos, relatórios e persistência continuam na suíte.

| Matriz | Cobertura executada |
|---|---|
| P05-01 a10 | 10 casos: identidade, escopo, estados, falta, repetição e concorrência |
| P04-01 a10 | 18 casos, incluindo repetições e teste da resposta de conflito |
| P03-01 a18 | 33 casos parametrizados/HTTP e3 casos da configuração da grade |
| Integração C01 aC04 | 4 casos: horário inválido concorrente, paciente errado concorrente, remarcação fora da grade e fluxo combinado |

Cada execução completa contém62 operações concorrentes, distribuídas em21 grupos,
com threads e transações `REQUIRES_NEW`. `CountDownLatch` só libera operações após
todas adquirirem conexões; `SELECT SESSION_ID()` comprova conexões H2 distintas.
Há limites de espera e verificação de término das threads. Não há transação única
do teste envolvendo as duas escritas. Rejeições marcam rollback, e contagens de
linhas/históricos são verificadas após a conclusão. Logs `AGENDA_RACE` registram
confirmações, rejeições, conexões e linhas/histórico adicionados; `AGENDA_STATE`
registra invariantes persistidas. A corrida cancelamento/criação admite as duas
ordens de serialização válidas, por isso sua distribuição pode variar.

Frontend Node22/npm11.6.2: `npm ci` e build são validados; a suíte unitária mantém
os7 casos reprovados e5 erros não tratados preexistentes de Q01–Q04. Não foram
alterados testes/componentes Angular para esconder esses resultados.

## CI e comandos locais

Workflow: **SIBRAPS CI**, PR para `main`, push na `main` e execução manual.
Jobs: backend Java21 (`verify` e artefatos Surefire), build frontend Node22/npm11.6.2
obrigatório e testes Angular diagnósticos opcionais na execução manual. Sem Secrets,
deploy, push/merge automático ou `continue-on-error`. Actions fixadas por SHA de
versões oficiais mantidas. O lint local não equivale a execução no GitHub Actions;
essa execução remota deve ser verificada no PR.

No diretório `backend/buscaativa`, JDK21:

```bash
bash ./mvnw test
bash ./mvnw verify
bash ./mvnw -Dsurefire.runOrder=random -Dsurefire.runOrder.random.seed=731 verify
```

Não é necessário apagar variáveis do terminal. Nenhum teste precisa de Git ou do
commit que continha a chave antiga. No Windows, use `.\mvnw.cmd` no lugar de
`bash ./mvnw`. Para desenvolvimento da aplicação (procedimento separado dos testes):

```bash
export JWT_SECRET_BASE64="$(openssl rand -base64 32)"
export DEV_DB_PASSWORD="$(openssl rand -base64 32)"
export SPRING_PROFILES_ACTIVE=dev
bash ./mvnw verify
java -jar target/buscaativa-0.0.1-SNAPSHOT.jar
```

Não use `set -x` com segredos. Para PowerShell e rotação, consulte
`docs/seguranca-p01-p02.md`. O console H2 continua desabilitado/sem módulo HTTP.

Frontend, em `frontend/sentinela-pet`:

```bash
npm ci
npm run build
npm test -- --watch=false # falhas conhecidas Q01–Q04 permanecem visíveis
npm start
```

## Limitações e revisão

Validar a CI remotamente e revisar as alterações antes de integrar. A API GitHub
disponível na sessão de implementação retornou403; isso não comprova execução de
workflow ou criação de PR. Não há alegação de validação no banco de produção.
Não houve alteração em entidades de domínio, autenticação/autorização por unidade,
dados reais ou funcionalidades Angular nesta etapa.
