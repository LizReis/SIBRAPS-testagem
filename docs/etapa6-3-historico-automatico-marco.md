# Histórico automático — marco técnico prospectivo (V20)

Base: d19f46348d9aa1a56238e0437e37ef05ddd6ec1d. A CI #45686166909 aprovou os três jobs.

V20 instala exclusivamente o armazenamento imutável do marco, sem gerar data ou inventário. A futura ativação autorizada usa uma ferramenta offline, sem ator administrativo fictício, sem arquivo externo e sem data retroativa informada. O inventário inicial contém vínculos por unidade canônica (identificadores internos apenas), incluindo unidades com lotação válida e sem vínculos; vínculos existentes sem usuários atuais também são preservados. O marco conserva zona, instante do banco, população e posição máxima da trilha V19. A operação é idempotente, não aceita trocar a zona e não cria fechamentos. V17–V19 permanecem compatíveis.

## Procedimento futuro (não executar em banco real nesta etapa)

Em cópia H2 em arquivo, offline, com backup restaurável e V19 válida, instalar V20 pela ferramenta AgendaMigrationTool com `--estrutura-marco-historico`. Somente quando a integração e o início da coleta tiverem sido autorizados, executar `--registrar-marco-historico` com a mesma ferramenta e zona institucional `APP_ALERTAS_ZONE` (padrão America/Bahia). A operação usa SET EXCLUSIVE 1 (sem expulsar sessões) e recusa outras sessões, em transação serializável. A confirmação do marco não implica que o mês esteja coberto. Nenhuma ativação automática no startup ou na migration.

## Limites

Esta é uma entrega intermediária, não um fechamento operacional. O marco V20 é separado do mecanismo manual V18, ainda não consumido pelo painel. Ainda faltam: trilha factual/cadastral independente, tratamento de unidades criadas após o marco, prova de continuidade/corte (inclusive commits concorrentes), consolidação automática com autoria técnica, substituição do fluxo manual e testes de isolamento integrados. A posição da sequência V19 e o timestamp de DML não provam ordem de commit em ambiente online. Nenhum mês é liberado por esta entrega. Não protege contra alterações DDL/TRUNCATE de administradores privilegiados. A data não deve ser registrada em produção antes da revisão completa. A execução dos novos testes depende da CI. Nenhum banco real foi usado, nenhum e-mail foi enviado, nenhum merge.
