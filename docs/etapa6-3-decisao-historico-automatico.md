# Etapa 6.3 — decisão sobre indicadores históricos e primeira correção complementar

## Decisão aprovada

O SIBRAPS deve produzir e consolidar automaticamente indicadores históricos por unidade, com cobertura comprovável e auditoria. Não se exige certificação mensal humana, arquivo externo, SQL ou geração manual de hash. O marco inicial somente será ativado quando a integração definitiva for autorizada; nenhum backfill de períodos sem prova. Mês sem cobertura deve permanecer indisponível; zero somente quando comprovado. A situação clínica de vínculos de outras unidades não pode contaminar a série. Preservar fechamentos já registrados e não alterar migrations existentes.

A V18 ainda contém o fluxo anterior de certificação. Não substituir inventário independente por uma segunda consulta à mesma trilha Envers, nem fabricar hash ou administrador técnico. A implementação automática precisa estabelecer um registro de cobertura prospectivo independente da reconstrução, coordenado com todas as escritas relevantes, inclusive criação, alteração, exclusão, frequência, modalidade, situação de rua e vínculo, e identificar lacunas. Definir o armazenamento de autoria técnica da consolidação, idempotência, recuperação e corte mensal. A retificação de períodos já consolidados permanece decisão separada. O envio externo RF26 permanece desativado.

## Correção técnica inicial

Base: `c2597d788eea6d27504229d5ad382321c8b84a9c`.

Ajustadas as suítes de App, MainLayout, DetalheProfissional, Inicio, Sidebar, Header e Pacientes para utilizar os contratos atuais, fornecer rotas e simular serviços de usuário, resumo e pesquisa. O teste antigo de título do scaffold foi substituído por uma verificação do RouterOutlet real; os demais preservam a criação e incluem asserções de comportamento. Nenhum código de produção ou regra clínica alterado. A CI da branch passa a executar a suíte Angular padrão completa em uma única invocação e mantém o contrato experimental dirigido.

A aprovação depende da execução do Actions. Não foram executados testes locais nesta entrega. A implementação do histórico automático, a jornada integrada das cinco unidades, a correção posterior ao fechamento e o ensaio de carga permanecem pendentes.
