# Etapa corretiva 3.1 — diagnóstico e validação antes da Etapa 4

## Objetivo e ponto de partida

Exclusivamente coerência risco/visita, responsabilidade histórica dos grupos e proteção do cancelamento genérico. Repositório LizReis/SIBRAPS-testagem; branch `correcao-fluxos-assistenciais-sibraps`; commit inicial `a2d1b98e8c848910cfaf63a65589b2f73e0d352b`, checkout limpo. Documentação das Etapas 0–3 e planejamento consultados antes das alterações. Baseline efetivamente executado com `bash ./mvnw verify`: **513 testes, zero falhas/erros/ignorados**, código 0. Evidências temporárias em `/tmp/sibraps-etapa3-1/baseline`.

## Diagnóstico anterior à implementação

**A:** o recálculo combina fatos individuais e frequências de sessões REALIZADAS, ordena por instante/origem/ID, reinicia a sequência a cada presença e deriva o gatilho de um par original/remarcação com ambas as faltas após a última presença. Duas faltas dão AMARELO e três VERMELHO; limites temporais existentes permanecem. Há uma exceção preexistente importante: INATIVO é classificado VERDE independentemente da sequência. Portanto não se deve afirmar que VERDE+gatilho seja impossível em todos os estados. Indicadores clínicos desconhecidos ficam provisórios e a lista defensiva mantém ativos vermelhos, com gatilho confirmado ou pendentes. Não há indicação inicial de defeito no cálculo; testes com comandos reais devem comprovar a conclusão, sem mudança gratuita da produção.

**B:** SessaoGrupo não guarda responsável histórico; AutorizacaoClinicaService e AuditoriaCorrecaoService usam o coordenador atual. Transferência posterior retira acesso de A e concede a B sobre sessões realizadas de A. A confirmação efetiva, sob locks já existentes (pacientes ordenados, grupo, sessão), é o ponto para consolidar o responsável validado. Planejado vínculo estável de sessão, separado do autor da correção, autorização histórica e DTO de leitura; sessões não realizadas acompanham o coordenador vigente. Migração aditiva posterior à V4 deve usar somente evidência Envers suficiente de transição para REALIZADA, nunca presumir o coordenador atual. Casos incompletos serão explicitamente pendentes e protegidos até reconciliação da Etapa 6. Auditorias antigas são imutáveis: seu snapshot, inclusive eventual coordenador registrado pela política anterior, não será reescrito nem tratado como prova infalível.

**C:** atualizarStatus permite CANCELADO para frequência PRESENTE/FALTOU, preservando a frequência canônica mas alterando a reserva. Há ainda uma guarda genérica que recusa qualquer comando em data futura, inclusive cancelamento sem frequência. A regra aprovada CA-01 exige exceção estritamente para cancelamento administrativo válido; as demais regras temporais não serão antecipadas. Planejado conflito 409 antes de idempotência/mutação para CANCELADO com frequência conhecida ou reserva SUBSTITUIDA. Mesmo serviço atende endpoint legado e chamada direta. P05 continua sendo substituição legítima, sem cancelamento genérico. Regularização de cancelamento indevido permanece na Etapa 5.

## Superfície afetada e preservação

Serviços: PacienteServiceImpl/PoliticaVisitaDomiciliar (verificação), AgendamentoServiceImpl, GrupoTerapeuticoServiceImpl, AutorizacaoClinicaService e AuditoriaCorrecaoService. Entidade SessaoGrupo e seus DTOs; repositórios/locks existentes de grupo, sessão, participante, paciente e usuário. Controllers existentes de grupos/agendamentos/auditoria permanecem entradas dos testes. Migrador offline H2 e novas migrações aditivas. Testes HTTP/JPA, migração em arquivo, Envers, concorrência e rollback. Não alterar handler global, JWT, H2, frontend, RF22, RNF17, limites de risco/visita ou V1–V4. Não iniciar Etapas 4–9, merge ou PR.

Resultados finais, arquivos e commits serão registrados após execução, sem declarar antecipadamente testes aprovados.

## Frente A — resultado comprovado

Onze novos testes passaram em `front-a-green`: dez de fluxo real HTTP/JPA e um de migração H2 antiga com frequência desconhecida. A-01 a A-09 cobertos, mais exceção INATIVO e limite temporal preexistente. A-02 resulta em duas faltas, AMARELO e visita; correção do original mantém SUBSTITUIDA e vínculo; correção do sucessor limpa o gatilho; outro par posterior conserva-o. Três faltas independentes geram VERMELHO sem visita. Confirmação efetiva de presença em grupo reinicia a cronologia. O relógio de teste é controlado somente para tornar a sessão de hoje determinística; nenhuma regra temporal de produção foi alterada.

A-08 executa a fixture SQL antiga existente e o migrador offline, inicia a aplicação com validate e prova revisão explícita, ausência de local de visita confirmada e recibo RNF17 com gatilho_confirmado=false. Frequências, versões e histórico assistencial não mudam. A-09 é o único teste de estado VERDE+gatilho artificial; a lista continua mostrando-o e o recálculo com evidência completa o corrige. A exceção INATIVO permanece VERDE+gatilho derivado, fora da lista operacional de ativos. **Nenhuma mudança na política de risco, visita ou seleção foi necessária.**

## Reprodução dos defeitos antes das correções B/C

Em `diagnosis-ab`, A foi recusada com 403 após transferência (esperado: autorização e validação do motivo), e B corrigiu a sessão antiga com 200 (esperado: 403). Em `front-c-red`, cancelamento futuro sem frequência retornou 422 para administrador, responsável e recepção; seis casos de cancelamento de PRESENTE/FALTOU desses perfis retornaram 200 quando a nova regra exige 409. Foram falhas funcionais reproduzidas, não erros de lock aceitos como conflitos de negócio.
