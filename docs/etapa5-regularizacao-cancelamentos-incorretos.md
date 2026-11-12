# Etapa 5 — regularização de cancelamentos incorretos

## Base, diagnóstico e matriz antes da implementação

Repositório LizReis/SIBRAPS-testagem, branch correcao-fluxos-assistenciais-sibraps, HEAD inicial 5415eb837dbc3db0f1c102cbf20082a735f773bc, checkout limpo. Relatórios das Etapas 0–4 e RN-05/RN-06/RN-10 consultados. Baseline executado: **mvnw verify, 726 testes, zero falhas/erros/ignorados**. Nenhuma execução em banco real.

O PATCH legado e as sobrecargas de atualizarStatus ainda permitem CANCELADA+NAO_REGISTRADA→AGENDADA/REMARCADA no próprio dia, sem motivo/auditoria específica, respeitando apenas oferta e a guarda de data (não horário). Datas futuras são recusadas por guarda genérica antiga. CANCELADA→frequência é recusada; a infraestrutura de correção não admite NR→P/F e não será enfraquecida para isso. setSituacaoAtendimento preserva a reserva canônica CANCELADA e mantém a projeção CANCELADO, mas não é uma operação de domínio explícita de regularização. Histórico genérico usa a projeção e não geraria o fato clínico correto quando CANCELADA continuar CANCELADA. Indicadores/repositórios clínicos já consultam frequência canônica, inclusive cancelamentos e substituições; ocupação canônica CANCELADA+P/F é false.

| Operação | Origem | Destino | Horário | Oferta | Auditoria |
|---|---|---|---|---|---|
| Recuperar reserva | CANCELADA/NR | AGENDADA/NR se raiz; REMARCADA/NR se sucessora íntegra | Estritamente futuro | Obrigatória | Nova regularização RECUPERACAO_RESERVA |
| Registrar fato | CANCELADA/NR | CANCELADA/P ou F | Estritamente após horário | Não condiciona fato; colisão efetiva bloqueia | Nova regularização REGISTRO_FATO_ASSISTENCIAL |
| Correção posterior | CANCELADA/P ou F com origem confiável | CANCELADA/F ou P | Política estrita Etapa 4 | Sem oferta; colisão protegida | Auditoria clínica existente P↔F |
| Legado genérico | CANCELADA | Recuperação ou primeiro fato | Qualquer | — | Recusado; operação específica obrigatória |

Exatamente no horário: não recuperar como futura e não regularizar retrospectivamente. Não há prazo máximo retrospectivo. Dados ausentes/ambíguos continuam pendentes. Recepção não regulariza; preserva cancelamento administrativo e registro inicial comum já autorizados.

Planejado: dois DTOs e operações específicas; locks paciente→profissional→atendimento; cadeia P05 validada sob lock do paciente; estado derivado do vínculo persistido, sem escolher reserva no payload; motivos/versão obrigatórios; auditoria insert-only própria com V7 aditiva, sem backfill. Histórico factual explícito usa data/hora assistencial e responsável, sem inventar evidência. Evento clínico já existente/contraditório exige reconciliação, não escolha automática. Correção posterior somente pela rota específica da Etapa 4 com origem auditada e sequência de versões/frequências compatível; CANCELADA+P/F legado não se torna confiável por presunção.

Colisões estruturadas reutilizam a Etapa 4; histórico incompatível e origem inconsistente também produzem pendência segura. Nenhuma fila/resolução da Etapa 6, interface da 8 ou reescrita global de HTTP da 7 será antecipada. V1–V6, constraints clínicas, risco/visita, RF22/RNF17, JWT/H2 e frontend permanecem protegidos.

Reprodução efetiva anterior à correção: cinco casos falharam em legacy-red — quatro requisições AGENDADO/REMARCADO, com e sem motivo, e uma chamada às sobrecargas diretas. O serviço antigo confirmou a recuperação indevida em vez de recusá-la. Foram falhas funcionais, não falhas técnicas de lock.
