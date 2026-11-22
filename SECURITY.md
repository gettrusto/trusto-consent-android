# Segurança

Este SDK roda dentro do aplicativo dos nossos clientes. Se você encontrou uma vulnerabilidade, conte para nós em particular antes de qualquer divulgação.

## Como reportar

Use o **reporte privado de vulnerabilidade** do GitHub, na aba **Security** deste repositório (botão **Report a vulnerability**). Não abra issue pública nem pull request com os detalhes.

Inclua, se puder:

- versão do SDK e do sistema operacional;
- o que acontece e o impacto esperado;
- passos para reproduzir ou uma prova de conceito.

Respondemos o recebimento em até 3 dias úteis e combinamos com você o prazo da correção e da divulgação.

## Assinatura dos pacotes

Os pacotes publicados no Maven Central (`io.gettrusto:consent-android`) são assinados com a chave OpenPGP "Trusto Consent SDK":

```
041714D8D7A67E9DA60A5236460134C150D5907D
```

A chave pública está em `keys.openpgp.org` e `keyserver.ubuntu.com`. Cada versão também tem atestação de proveniência do GitHub, ligada ao commit e ao workflow de lançamento.

## Versões cobertas

Só a versão mais recente recebe correção de segurança.
