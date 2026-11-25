# Histórico automático — diagnóstico de reconciliação

Base: 859173bc8ec0c5938664da187ba1e2ad4091983b. CI #45690581515 aprovada.

O diagnóstico read-only `--diagnostico-cobertura-historica` compara, em transação serializável, as assinaturas da base contemporânea, os movimentos posteriores capturados pela V21, as revisões Envers e o estado persistido das sete fontes. Verifica a cadeia anterior/posterior, divergências nas transições, cardinalidade e fronteira temporal ambígua. O relatório contém somente contagens por fonte, sem IDs, assinaturas ou dados cadastrais. Não cria novas migrations, marcos, revisões ou fechamentos. Preserva APIs, regras clínicas e envio RF26 desativado.

Teste sintético: base ausente, alteração cadastral sem Envers, revisão correspondente e revisão extra sem evidência independente. A aprovação depende do Actions.

## Limitações deliberadas

O resultado é diagnóstico global técnico, não autorização para um fechamento. Revisões Envers podem consolidar múltiplos DML da mesma transação; a comparação conservadora pode detectar divergência mesmo em operações legítimas. Dados anteriores ao marco sem revisões equivalentes também podem divergir. Não existe atribuição por unidade neste diagnóstico nem prova da ordem de commits na virada do mês. A igualdade de milissegundo ou inversão de revisões na fronteira exige investigação. O próximo pacote deve resolver corte temporal confiável, escopo de evidência por unidade, composição do inventário e tratamento de fontes legadas, e ligar a consolidação automática ao painel sem exigir certificação manual. Não se deve liberar valores históricos apenas por este diagnóstico retornar consistente. Nenhum banco real alterado ou marco ativado; sem merge.
