# Etapa 6.3 — pendências, numeração histórica e recálculo local

Base `1ba41de8a266c411000c39d2cf8c5dd532171301`: 1.338 Java + 12 concorrência + 28 Angular, build aprovado (Actions #45483147928). CI do pacote pendente.

## Pendência e evidência V15
Migration aditiva offline V15, coluna nullable em vínculo e Envers, sem backfill ou revisão artificial. NULL não prova causa. Somente empate entre frequências locais comprovadas, sem outros impedimentos de sequência ou cadeia, sinalizado quando a pendência anterior era explicitamente falsa, recebe marca de causa. Uma causa desconhecida/adicional invalida a marca. Não promove pendência antiga. A limpeza exige marca auditada em revisão anterior, empate ainda existente antes da correção factual autorizada sob os locks, resultado integralmente conclusivo depois da correção, e ausência de solicitação V12 local (V13 não comprova aplicação). A remoção e o recálculo são atômicos e auditados. Não limpa por simples passagem do tempo, presença posterior, aprovação administrativa, desaparecimento isolado de registro ou transferência. Outras causas e tickets permanecem bloqueados até existir prova estruturada de resolução/aplicação. Não muda o fluxo Opção B.

## Numeração
Depois de gravar fato e histórico individual ou coletivo, renumerar históricos coerentes dos fatos locais em ordem cronológica, presença reinicia contador; número somente em faltas. Correção retroativa renumera também os posteriores, sem mudar conteúdo factual/autoria/origem, com Envers na mesma transação. Não inventa histórico nem altera registros externos, ausentes, duplicados, inconsistentes ou com hora divergente. Origem incerta, sequência inconclusiva ou faltas simultâneas sem ordem cronológica comprovada bloqueiam a numeração (não usar ID como prova da ordem). Não é backfill geral. Outros escritores ainda não coordenados impedem uso operacional. Históricos anteriores que não podem ser renumerados precisam de análise antes do corte.

## Recálculo
Estado não ativo comprovado mantém verde e visita desligada, inclusive quando os fatos são inconclusivos. Não confundir efeitos do encerramento com ausência de faltas; preservar pendência, contador e última presença inconclusivos. Ativo conserva política de risco/RF22 e reativação volta a calcular pelos fatos locais. A projeção somente leitura compartilha os efeitos do encerramento. Nenhum efeito transversal de óbito.

## Validação
Quatro testes integrados novos: renumeração mista e revisão anterior, resolução por correção de empate local, preservação de pendência sem causa após alta, recálculo/reativação de alta com faltas. Teste V13→V14 preservado por target e acrescido de V14→V15 sem backfill, restrição de integridade e reexecução idempotente; testes que validam a versão mais recente atualizam a expectativa do número de migrations. Nenhum teste executado localmente. Sem alteração de V1–V14, frontend, rotas reais, notificação ou main.

## Limites
Não conclui a resolução de toda espécie de pendência: registros antigos de origem indeterminada, demais inconsistências, V12/V13 sem aplicação comprovada e causas desconhecidas precisam de mecanismo de revisão específico ou evidência adicional. Não declarar toda a etapa concluída nem ativar escritores. Próximo passo: validar CI e preparar integração coordenada dos atendimentos e grupos, incluindo prova de resolução para outras causas quando possível.

## Resultado da primeira CI e ajuste de expectativa

Actions #45485189042, commit `4669850`: 1.342 testes Java executados, 1.341 aprovados, uma falha e zero erros/ignorados. Os 16 testes de PersistenciaIndicadoresLocaisIntegrationTests e os dois de EventoEstadoClinicoVinculoMigrationTests passaram. Angular e build aprovados; matriz adicional de concorrência não executada devido à falha de verify. O diagnóstico consolidado de cópia restaurada V13 ainda esperava uma migration, mas recebeu duas (V14 e V15). Corrigido somente o nome, a expectativa e os identificadores locais desse cenário de versão mais recente, mantendo as verificações diagnósticas, snapshots e leitura exclusiva. Nova CI pendente; nenhuma alteração de código produtivo ou migrations nesta correção.
