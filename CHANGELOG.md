# Changelog

## 0.1.0 (a publicar)

- Modo sem tela: banner publicado do aplicativo, decisão local, fila de envio com reenvio.
- `configure`, `loadConfig` e `awaitConfig`, `needsConsent`, `acceptAll`, `rejectAll`, `save`,
  `revoke`, `isGranted`, `addListener`, `flush`, `visitorId`.
- Identificador aleatório do visitante fora do backup; nenhum identificador do aparelho.
- Reenvio com espera crescente; 401 e 404 no envio esperam a partir de 1 h; só 400, 403 e 413
  descartam.
- `isGranted`: obrigatórias sempre, opcionais só com decisão dentro do prazo.
- `addListener` avisa na hora com a decisão atual; `configure` repetido reaproveita a instância.
- Falha de disco nunca sai da API; arquivos com versão de formato.
- Endereço só https (http só para o aparelho ou o emulador, em app debuggable).
- Build com actions fixadas por SHA, wrapper validado e dependências verificadas.
