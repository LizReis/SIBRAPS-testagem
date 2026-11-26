# Etapa 6.5 — plano de tratamento das ambiguidades históricas

Referência: c74641ff559fa64cf95f5fe5e3a5691ecf8720a8, CI #45745662307 aprovada. Este plano inicia a 6.5 sem repetir o levantamento da 6.3, sem abrir acesso a dados inconclusivos e sem executar migração. Reutilizar [levantamento](etapa6-3-levantamento-origem-registros-antigos.md), [contrato do legado](etapa6-3-contrato-revisao-legado-inconclusivo.md), preflight, verificadores de proveniência e projeções atuais.

## Regras já acordadas

- Identidade global não concede acesso clínico entre unidades. Administrador analisa casos comprovadamente locais; profissional e recepção não recebem permissão nova de investigação de origem desconhecida.
- Se houve atendimento, procurar a origem da fonte real e a responsabilidade histórica, não a lotação atual, CAPS/USF de referência, endereço, outro atendimento da pessoa ou vínculo cadastral isolado. Um campo vazio não é permissão de acesso.
- Sem prova suficiente, preservar o fato inconclusivo, fora da contribuição conclusiva; não interpretar como ausência, presença, zero ou unidade arbitrária. Cadeia inválida não aciona visita confirmada pela remarcação. Elegibilidade por risco vermelho comprovado continua independente.
- Opção B: justificativa digitada diretamente no SIBRAPS, solicitação, decisão e aplicação distintas. Sem anexos obrigatórios nem evidência fabricada. Não reescrever meses consolidados.
- Exclusão autorizada apenas de exemplos comprovadamente sintéticos quando necessária; não há motivo identificado para exclusão nesta entrega. Preservar fixtures negativas de migração. Não inspecionada base institucional.

## O que já existe

V8 preflight agregado; diagnóstico V13 e levantamento anteriores; proveniência local/validação de cadeia e responsabilidade; revisão V12/V13 para fatos comprovados; projeção inconclusiva e V23–V26 para fatos locais coincidentes. O verificador de revisão rejeita HISTORICO com origem vazia, inclusive se uma fonte tem unidade gravada. Esta é uma barreira de autorização, não um erro a contornar. ClassificadorEvidenciaClinicaLocal não é prova nem concede acesso. Não executar backfill com os dados das fixtures.

## Pacotes propostos (sujeitos às decisões ainda necessárias)

| Pacote | Escopo | Critério |
|---|---|---|
| 6.5A — especificação incremental | Consolidar matriz de casos/prova e portões | Este documento; não cria acesso nem alteração |
| 6.5B — fonte local comprovada | Começar por histórico sem origem própria mas fonte individual/coletiva comprovada, mesma pessoa e consistência temporal; depois tratar fonte local com metadados incompletos | Aprovar política de prova e acesso antes de abrir leitura do histórico; avaliação de servidor, nunca aceitar unidade informada pelo cliente |
| 6.5C — casos sem prova/conflitantes | Tratar origem, responsabilidade, horário, frequência e cadeia incertos | Não atribuir unidade, não abrir conteúdo identificável a unidade não comprovada; deliberação/registro de insuficiência só se autorizado |
| 6.5D — aplicação e consumidores | Se houver autorização explícita, aplicar correção comprovada como ato auditado separado; recalcular apenas vínculo afetado | Preservar fato original, revisões, evidência, transação, reversibilidade, isolamento, histórico mensal fechado; sem correções por inferência |
| 6.5E — validação e encerramento | Cenários sintéticos de isolamento, provas conflitantes, cadeia inválida e rollback; reaproveitar suites já aprovadas | Nenhum vazamento ou contagem inventada; registrar ressalvas e seguir 6.6 |

## Matriz para decisão funcional antes de código

| Caso | Tratamento seguro atual | Proposta a validar |
|---|---|---|
| H1: histórico sem unidade, fonte local inequívoca, mesma pessoa, cadeia e frequência coerentes | Candidato somente para conferência; permanece bloqueado | Administrador da unidade da fonte comprovada revisa a associação com justificativa digitada; não alterar automaticamente |
| H2: só lotação atual do profissional ou outro vínculo | Inconclusivo | Continuar bloqueado, sem atribuição nem acesso ao conteúdo |
| S1: sessão com responsável recuperado, mas origem desconhecida | Inconclusivo | Não tratar responsável como prova de unidade |
| A1: fontes contraditórias ou elo de remarcação inválido | Inconclusivo | Não escolher lado nem ativar gatilho RF22 por cadeia inválida |
| Fonte local comprovada, frequência/horário/autor incertos | Inconclusivo para contribuição | Administrador analisa evidência existente; não inventar frequência, horário ou autor; definir aplicação/reversão específica antes de escrever |

Portão: falta ratificar se a fonte local íntegra constitui prova suficiente para abrir a análise do histórico sem origem própria (H1), quais demais evidências internas contemporâneas são admissíveis, e procedimento de encerramento/reabertura quando insuficientes. A autorização prévia de analisar casos comprovados não é autorização de atribuição de origem. Não criar papel global, fila de conteúdo sem origem, migração de aplicação, endpoint de edição nem novos estados por conta própria. Uma vez ratificado H1, executar pacote 6.5B direcionado com testes sintéticos e regressão, sem repetir inventário. Se nenhuma prova existir, manter proteção atual; não exigir anexos. Telas reservadas à 8.

Entrega documental, sem alterar código/schema/dados, sem necessidade de nova execução de código. Nenhum Codex, PR ou merge.
