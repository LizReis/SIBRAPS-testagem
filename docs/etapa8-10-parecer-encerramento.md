# Etapa 8.10 — parecer de encerramento técnico

Base validada: 9cc73d855ef1857d5a7873b595d9dcb52bb99111. CI #46108520453, 28/11/2026: todos os jobs aprovados; 1448 testes Maven verify, 12 invocações de concorrência separadas, 240 testes Angular (42 arquivos), 6 testes do contrato experimental, ambos builds, cinco cenários Chromium com fixtures HTTP e 32 fases de navegador com Spring Boot real e H2 em memória. Artefatos #15248109631 (integração) e #15249205202 (visual). Evidências na CI; expiram conforme retenção. Não somar testes como casos necessariamente únicos.

## Rastreabilidade da jornada

| Fases | Escopo | Relatório |
|---|---|---|
| 1–5 | Login, cadastro único, confirmação de vínculo, edição compartilhada, duas USFs | etapa8-10-integracao-cadastro.md |
| 6–8 | Agenda, frequência individual, correção e remarcação | etapa8-10-integracao-agenda.md |
| 9–11 | RF22 por remarcação, visualização RNF17, correção retroativa | etapa8-10-integracao-rf22.md |
| 12–14 | Grupos, frequência provisória, confirmação, correção e auditoria | etapa8-10-integracao-grupos.md |
| 15–17 | Recorrência semanal, cancelamento, inscrição retroativa | etapa8-10-integracao-serie-grupos.md |
| 18–20 | Solicitação de revisão, aprovação e rejeição, recusa de decisão repetida | etapa8-10-integracao-pendencias.md |
| 21–23 | Duplicidade comprovada, resolução, reversão e preservação dos fatos | etapa8-10-integracao-reconciliacao.md |
| 24–26 | Origem histórica, reconhecimento, reversão, reabertura e nova decisão | etapa8-10-integracao-origem.md |
| 27–29 | Risco vermelho, moradia, encaminhamento, RNF17, idempotência e recálculo | etapa8-10-integracao-busca-ativa.md |
| 30–32 | Identidade em USF A/B e CAPS AD, prontuários, atendimentos, grupos, correções, auditorias e painel isolados | etapa8-10-integracao-usf-caps.md |

## Parecer e limites

Aprovada tecnicamente a Etapa 8.10 no escopo exercitado e encerrada a Etapa 8 para continuidade da 9. Não é homologação de produção ou garantia de ausência de defeitos. As jornadas reais usam build experimental, servidor test-only com superfície HTTP restrita, fixtures sintéticas, relógio controlado, H2 create-drop e estruturas V23–V31 instaladas de forma dirigida. Não equivalem a execução do artefato de produção com histórico Flyway completo no SGBD definitivo. O build padrão e cinco cenários simulados passaram separadamente. CAPS AD representativo; não certifica todas combinações CAPS I/II. Não exaure recorrências, transferências de coordenação, condições RF22, acessibilidade WCAG, relatórios/exportações ou todas combinações de perfis. Manter testes de backend existentes, verificar lacunas relevantes na etapa 9 sem multiplicar cenários sem objetivo.

A revisão estética e aceite humano das telas no Codespace seguem expressamente pendentes. Segurança de dependências: npm ci registrou 48 (2 baixas, 22 moderadas, 21 altas, 3 críticas), ainda sem inventário detalhado; não afirmar exploração ou exposição em produção. Revisar também dependências Java, configuração de implantação, segredos, logs, migrations, CI definitiva e compatibilidade da API/contrato padrão antes de liberar. Sem banco real, deploy, PR ou merge na main. A decisão de liberar/mesclar é posterior e depende de aceite explícito.
