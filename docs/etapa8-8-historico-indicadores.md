# Etapa 8.8 — primeira entrega: histórico e indicadores locais

Base 2da7998c618deed472b5a491a4e310ba289d95d8, CI #45941719793 aprovada. Encerramento técnico do pacote 3 em etapa8-6-8-7-parecer-encerramento.md.

DTOs de ConsultasClinicasLocaisEnsaioService e BuscaAtivaPainelLocalEnsaioService: totais presença/falta do prontuário são Long anuláveis (resolução inconclusiva), última presença possui indicador explícito de conclusão, eventos/sessões e atendimentos desconsiderados vêm da API. Preservar valores; distinguir nulo de zero, nenhuma presença comprovada de presença não confirmada; indicar eventos/sessões desconsiderados e origem reconhecida, não tratar sessão não realizada como frequência confirmada; explicitar escopo local e cobertura incompleta. Preservar lista de atendimentos desconsiderados no adaptador, sem inferir status de eventos não marcados. Não recalcular indicadores no cliente. Limpar histórico após erro e cancelar consulta anterior.

Painel: cancelar leitura superada por novo filtro, limpar dados em erro, opção padrão Minha unidade, separar estados não confirmados do total ativo, mensagem vazia limitada à consulta. Gráfico histórico conserva lacunas; não alterar marco/fechamentos ou iniciar coleta oficial. Cinco testes dirigidos (dois histórico, um contrato HTTP, dois painel), não executados localmente. CI existente primeira validação. Nenhum teste removido.

Faltam revisão de relatórios/busca ativa RNF17/RF22, apresentação de totais nas demais páginas, permissões e responsividade, jornadas integradas e parecer final. Não habilitar RF26 externo, não alterar gatilhos, não iniciar etapa 9. Sem PR/merge.
