# 6.5B — contrato HTTP de aplicação, reversão, reabertura e auditoria

Base 1407108, CI #45863604286 aprovada. Sem migration. Reutiliza os escritores transacionais V28–V31. Base /api/pacientes/{paciente}/vinculos/{vinculo}/revisoes:
- POST /{ticket}/origem/aplicar?version=versão do vínculo
- POST /origem/aplicacoes/{aplicacao}/desfazer?version=versão do vínculo
- POST /{ticket}/origem/reabrir?version=versão do vínculo
- GET /{ticket}/origem/auditoria

Todos exclusivos de administrador da unidade comprovada. Corpo dos POST: {justificativa}; resposta 201 {id}, sem cache. Não receber origem, ator, frequência nem prova pelo cliente. GET 200 sem cache, por ticket local H1; retorna versão, solicitação, decisão, prova histórica, aplicação/reversão e ligações anterior/posterior, ator por UUID público, justificativas, instantes e estado de reconhecimento/integridade. Dados auditados não certificam validade atual: INCONCLUSIVA indica divergência; integridadeEventos diz respeito aos eventos V31, não certifica aprovação isolada ou fonte atual. Não consultar fonte atual de outra unidade. Mantém HTTP 401/403/404/409/422 atuais, padronização global na etapa 7. Solicitação e decisão permanecem nas rotas anteriores; nenhuma nova permissão.

Reaproveita cenário integrado V31: troca aplicação/reversão/reabertura bem-sucedidas por duas chamadas HTTP simultâneas em cada operação (uma 201, outra 409/422), mantém rollback interno e asserções originais, checa 401/403/404, versão obsoleta, justificativa inválida, auditoria antes/depois, aplicação coletiva pela API e trilha sem evidência inconclusiva. Não executado localmente. A concorrência em uma instância não certifica volume ou implantação distribuída. Sem correção automática de origem, nenhuma frequência duplicada, sem alteração de meses fechados. A aprovação CI será pré-condição de encerramento técnico 6.5B e início da 6.5C (prova insuficiente/conflitante), mantendo as decisões funcionais pendentes. Na etapa 8 inventariar rotas e disponibilizar todas as operações humanas em telas conforme perfis, sem refazer funcionalidades existentes. Sem dados reais, Codex, PR ou merge.
