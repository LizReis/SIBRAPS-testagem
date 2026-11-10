# Pré-Etapa 3 — correção da seleção de busca ativa

## Base, causa e regra

Repositório LizReis/SIBRAPS-testagem, branch `correcao-fluxos-assistenciais-sibraps`, checkout inicialmente limpo em `6ad7e0c18b59e8292e7748a358cf92ec3fe7ed38`. Ancestralidade confirmada e documentação das Etapas 0/1/2 consultada. Execução em 10/11/2026, com horários apresentados em America/Sao_Paulo. Nenhum banco real foi acessado ou reinicializado.

O endpoint `GET /api/pacientes/busca-ativa` selecionava somente VERMELHO. Assim, duas faltas vinculadas (original e remarcação), que legitimamente acionam visita mantendo risco AMARELO, ficavam fora da lista. A classificação de risco e a seleção de acompanhamento são critérios independentes.

A seleção agora exige paciente ATIVO e escopo autenticado, mais uma destas condições: risco VERMELHO; gatilho confirmado; ou indicadores pendentes, exibidos exclusivamente como revisão. Um gatilho pendente não confirma necessidade de visita. Nenhuma classificação é alterada para forçar inclusão; limites anteriores de faltas e dias sem presença foram preservados, assim como o recálculo de risco sob demanda existente.

## RF22 e consumidores

- Endereço fixo: `Visita domiciliar necessária`, com endereço cadastrado.
- Situação de rua: `Busca em ponto de referência necessária`, usando o complemento do endereço como referência existente, sem exigir endereço residencial.
- Sem referência: `Nenhum ponto de referência cadastrado — verificar com a equipe antes de acionar a busca.`
- Histórico incompleto: `Revisão do histórico necessária`, sem expor endereço como local de uma visita confirmada.

Foram examinados controller, repositório, DTOs, painel, histórico de risco, resumo inicial, relatórios e consumidores Angular. O painel já incluía amarelos e pendentes, mas perdia VERDE com gatilho confirmado: seu conjunto de prioritários foi ampliado, mantendo ordenação, limite de cinco e distribuição estatística. A query compartilhada pelos relatórios PDF/Excel também tinha filtro exclusivo VERMELHO; agora inclui gatilhos confirmados, mantendo datas, profissional, acompanhamento e escopo. Um indicador provisório, sozinho, não é promovido a visita na exportação.

`PainelHistoricoServiceImpl` reconstrói distribuição das classificações via Envers; isso não é lista de busca ativa e foi preservado. O resumo inicial mantém a contagem de atenção por AMARELO/VERMELHO; nenhuma estatística foi rebatizada. Os métodos antigos de prioridade no repositório sem consumidores ativos não foram redefinidos. O Angular atual não foi redesenhado: UI completa e validação integrada de resumos/relatórios continuam nas Etapas 8/9.

## Testes de integração

`BuscaAtivaSelectionTests` acrescenta 19 execuções HTTP/JPA:

- BA-01–06: seis combinações de risco e gatilho, verificando inclusão, ausência de duplicidade e classificação persistida.
- BA-07 e BA-14: correção real de frequência remove/recoloca o paciente na lista, sem criar reserva adicional.
- BA-08–10: mensagens RF22, referência sem endereço residencial e aviso exato quando ausente.
- BA-11/12: endereço externo não exposto e paciente inativo excluído.
- BA-13: dois casos de indicador provisório, com gatilho anterior falso/verdadeiro, permanecendo visíveis para revisão.
- Painel: verde com gatilho entra nos candidatos prioritários, sem virar vermelho.
- Excel: três casos validam o arquivo exportado e distinguem gatilho confirmado, provisório e ausente.

Antes da correção, 16 execuções iniciais registraram 10 falhas e 6 sucessos; o teste do painel também precisou isolar os outros pacientes fictícios antes de conferir seus totais. Após a correção, o focado com visita/isolamento passou 95/95. O focado final, incluindo os três casos de Excel adicionados, passou 19/19 (14,854 s, término 18:08:37). O baseline da Etapa 2 foi reproduzido com 382/382 no verify (42,928 s, término 18:05:22), sem falhas, erros ou ignorados.

Verify completo da Entrega A: **401 executados, 401 aprovados, zero falhas/erros/ignorados**, BUILD SUCCESS em 41,177 s, término **18:10:33 de 10/11/2026**. Os 382 casos anteriores permanecem aprovados; foram acrescentados 19. A entrega foi validada antes de qualquer alteração de autorização/justificativa/auditoria da Etapa 3.

## Arquivos e escopo

Alterados `PacienteServiceImpl.java`, `PainelServiceImpl.java`, `PacienteRepository.java`; adicionados `BuscaAtivaSelectionTests.java` e este documento. Não foram alterados estados canônicos, limites de risco, RF22, autenticação, JWT, console H2, grade, bloqueios, handler global, frontend, migrações publicadas ou seeds. A Entrega B começa somente após o verify completo e o commit específico desta entrega.
