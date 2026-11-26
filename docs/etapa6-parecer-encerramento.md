# Etapa 6 — parecer de encerramento técnico

Data: 26/11/2026. Branch correcao-cadastro-unico-isolamento. Referência de código validada: 75e67438df3920212de63995de165d9706cca74f. CI [#45892744210](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45892744210), concluída com sucesso. Main conferida: 967d5162dd543ec9005608739128423903e75e30.

## Evidência final
- Maven verify: 1430 testes, zero falhas, erros ou ignorados.
- ReconciliationConcurrency61Characterization opt-in: 12 testes, zero falhas, erros ou ignorados.
- Angular padrão: 131 testes em 32 arquivos; contrato experimental: 4 testes em um arquivo; ambos aprovados.
- Builds Angular de produção e experimental aprovados.
- Relatório nominal e correções: docs/etapa6-6-validacao-final.md. A comparação de 63 invocações de 6.1 e oito complementares de 6.2 distingue coberturas diretas, parciais e observações. Não há correspondência integral demonstrável com os XMLs históricos dos 925 casos da 5.1.
- RF07: original ainda agendado não confirma gatilho de visita. Frequências provisórias de sessões agendadas locais comprovadas não invalidam indicadores conclusivos existentes. Regressões promovidas para seleção permanente.
- RESP-09: designação V32 distinta da autoria efetiva; transferência preserva sessões anteriores, sinaliza autoria incerta e impede confirmação ordinária. Sem backfill por suposição. A guarda de sessão realizada sem responsabilidade comprovada foi testada inclusive para administrador.
- Compatibilidade V32: preparação explícita apenas do DDL aditivo em fixtures históricas para uso do mapeamento atual, sem registrar V32 nessas fixtures como migration executada. Ensaios de instalação operacional passam pela cadeia explícita até V32. Não confundir fixture híbrida com prova de boot de aplicação atual em esquema antigo puro. Nenhuma alteração de migrations V1–V32 no último pacote.

## Parecer
APROVADA COM RESSALVAS para encerramento técnico da Etapa 6 (6.1–6.6), limitado ao ambiente e às combinações testadas, não homologação para produção. A regressão integral do pacote foi conferida nos logs, sem nova execução local ou remota para esta documentação.

Ressalvas: (1) regularização de autoria após transferência continua sem caminho completo de aplicação quando não há prova efetiva; não inventar evidência nem desbloquear sessão por justificativa isolada. Antes de implementar regularização adicional, validar critério institucional e fluxo autorizado; manter sessão protegida. (2) 24 combinações da matriz original tinham cobertura parcial na primeira entrega 6.6; não tratar ausência de falha comprovada como cobertura exaustiva. (3) conflitos por instante, não duração; destino de reservas futuras após encerramento do vínculo e política de conflitos entre unidades não decididos. (4) marco real de coleta ainda não ativado, RF26 externo não ativado. (5) interface humana de todas as operações, aceite de usabilidade, SGBD definitivo, volume, backup/restauração e validação operacional na 8–9. Preservar os pareceres 6.3–6.5. Sem dados reais, PR, merge ou implantação.

## Próxima etapa
Etapa 7, tratamento de erros do backend, conforme plano-etapa0-fluxos-assistenciais.md: distinguir regra de negócio, concorrência recuperável, infraestrutura e defeito interno; preservar 401/403/404, validações, contratos de resposta, segurança e diagnóstico interno. ResourceExceptionHandler, StandardError, GlobalConcurrencyHandlerReviewTests e traduções de exceções são referências iniciais. Reaproveitar CI atual, agrupar mudanças e rodar testes dirigidos; regressão integral no pacote final. Não alterar regras clínicas nem antecipar telas da Etapa 8. Não disparar CI apenas por este parecer.
