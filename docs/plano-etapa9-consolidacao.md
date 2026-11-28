# Etapa 9 — validação final, segurança e consolidação

Base funcional aprovada 9cc73d855ef1857d5a7873b595d9dcb52bb99111, CI #46108520453. Etapa 8 encerrada tecnicamente conforme etapa8-10-parecer-encerramento.md; não reabrir etapas sem lacuna ou regressão demonstrada. Manter branch correcao-cadastro-unico-isolamento e main intacta.

## 9.1 — Segurança de dependências e triagem (iniciada)
Coletar npm audit completo e --omit=dev em JSON a partir do lockfile, sem instalar a aplicação ou alterar dependências; distinguir pacote direto/transitivo, cadeia, severidade, correção disponível e alcance provável (build, testes, bundle, runtime). Não confundir omit=dev com composição do bundle ou prova de exploração. Workflow diagnóstico separado, não repetir as suítes já verdes por documentação/coleta. Falhas de consulta externa devem ser visíveis; exit 1 por achados válidos não equivale a erro de coleta. A aprovação desse job não é aprovação de segurança. Triar Java/Maven (árvore resolvida, dependências runtime/teste, avisos verificáveis em fontes confiáveis) antes de fechar 9.1. Não executar npm audit fix --force ou upgrades major sem análise. Agrupar correções compatíveis, preservar lockfile coerente, validar regressão integral uma vez por pacote. Riscos residuais exigem justificativa e aceite, especialmente críticos/altos.

## 9.2 — Configuração e prontidão operacional
Revisar contrato padrão x ensaio e superfície HTTP real, JWT/rotação e material histórico revogado, console H2, CORS/TLS, logs sem dados clínicos ou segredos, variáveis, permissões/isolamento, notificações, CI principal e configuração de banco/driver. Não ativar dev para dados reais. Revisar segurança P01/P02 e riscos remanescentes pelos relatórios anteriores. Escolha do SGBD e infraestrutura definitiva requer decisão explícita; não inventar credenciais nem conectar banco real. Validar migrations completas e recuperação/backup/rollback em banco descartável representativo, não confundir com H2 de jornada. Planejar marco inicial dos indicadores históricos conforme acordo, sem certificar dados históricos de exemplo manualmente.

## 9.3 — Regressão consolidada e documentação
Inventariar lacunas relevantes e rastrear evidências anteriores; corrigir somente defeitos confirmados, sem introduzir regras clínicas. Executar CI integral no candidato final, incluindo backend, concorrência, Angular, contratos, builds, navegador e 32 fases; adequar cobertura se risco confirmado. Consolidar relatório final, instruções de execução, configuração, limitações e roteiro de homologação. Revisão visual e usabilidade humana no Codespace e aceite pela equipe continuam pendentes; não substituir por PNGs ou testes automatizados.

## 9.4 — Gate de liberação (não autorizado nesta etapa)
Somente após triagem e resolução/aceite dos riscos, configuração e banco aprovados, regressão e homologação humana, apresentar parecer à usuária e solicitar autorização explícita para integração/main/deploy. Não fazer merge, PR, deploy, excluir dados ou mudar regras de negócio por iniciativa própria. Etapa 9 não está concluída apenas com execução verde.

## Estratégia
Preferir pacotes consolidados; não disparar CI completa por documentação ou diagnóstico isolado. Reutilizar workflows e evidências existentes. Sem Codex nesta rodada. A revisão estética permanece reservada ao Codespace.
