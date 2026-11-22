# Trusto Consent para Android

SDK de consentimento LGPD para aplicativos Android. Registra a decisão de cada pessoa como
prova, com a mesma regra do banner do site: a versão do banner que ela viu, as finalidades
aceitas e recusadas, data e prazo.

Esta versão é o modo **sem tela**: o aplicativo desenha a própria tela de consentimento a
partir do banner publicado, e o SDK guarda a decisão no aparelho e a envia. A tela pronta
vem numa versão seguinte.

## Antes de começar

1. Em **Consentimentos > Aplicativos**, cadastre o aplicativo Android com o nome do pacote e
   a impressão SHA-256 do certificado de assinatura da loja.
2. Publique o arquivo `/.well-known/assetlinks.json` que a tela mostra no domínio verificado
   escolhido. A posse é conferida a partir dele.
3. Com a posse verificada, gere a **chave publicável** (`trusto_pk_live_…`). Ela não é
   segredo: só grava decisões deste aplicativo e lê o banner publicado dele.

## Instalação

Requer Android 6.0 (API 23) ou mais novo. Sem dependências além da plataforma e da
biblioteca padrão do Kotlin.

```kotlin
dependencies {
    implementation("io.gettrusto:consent-android:0.1.0")
}
```

> A publicação no repositório central de pacotes ainda não foi feita. Até lá, use o AAR
> gerado por `./gradlew :consent:assembleRelease`.

## Uso

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        TrustoConsent.configure(this, "trusto_pk_live_...")
        // Chamado na hora com a decisão atual (mesmo sem nenhuma) e depois a cada mudança:
        // os SDKs de terceiros já começam no estado certo.
        TrustoConsent.addListener { applyConsent() }
    }

    private fun applyConsent() {
        // Ligue ou desligue os SDKs de terceiros conforme a decisão.
        analytics.enabled = TrustoConsent.isGranted("analytics")
        ads.enabled = TrustoConsent.isGranted("marketing")
    }
}

// Na primeira tela:
TrustoConsent.loadConfig { result ->
    result.onSuccess { config ->
        if (TrustoConsent.needsConsent) showConsentScreen(config) // a tela do aplicativo
    }
}

// Nos botões da tela:
TrustoConsent.acceptAll()
TrustoConsent.rejectAll()
TrustoConsent.save(mapOf("analytics" to true, "marketing" to false))

// Em "Gerenciar consentimento", nas configurações do aplicativo:
TrustoConsent.revoke()
```

Com corrotinas, `TrustoConsent.awaitConfig()` faz o mesmo que `loadConfig`.

| Chamada | O que faz |
|---|---|
| `configure(context, chave, endpoint)` | Prepara o SDK e retoma o envio do que ficou pendente. Chame no `Application`; repetir com a mesma chave e o mesmo endereço não faz nada. `endpoint` serve para testar em outro ambiente e precisa ser https (http só para o aparelho ou o emulador, em app debuggable) |
| `loadConfig { }` / `awaitConfig()` | O banner publicado. Cache de 60 s; sem rede, o último salvo |
| `needsConsent` | `true` sem decisão, com banner de outra versão ou com a decisão vencida |
| `acceptAll()`, `rejectAll()`, `save(map)`, `revoke()` | Grava a decisão. Vale no aparelho na hora e é enviada em segundo plano. Depois que o banner muda no servidor, pedem um `loadConfig` novo antes (`ConfigNotLoaded`) |
| `decision` | A decisão em vigor, com o recibo (`consentId`) quando o envio foi confirmado |
| `isGranted(chave)` | Se a finalidade está concedida. Obrigatórias sempre; opcionais só com decisão dentro do prazo |
| `addListener { }` | Avisado na thread principal na hora, com a decisão atual, e depois a cada decisão, recibo ou banner novo |
| `flush()` | Tenta enviar agora o que estiver pendente |
| `visitorId` | O identificador que o titular informa num pedido de direitos |

Os erros são `ConsentException`: `InvalidKey`, `InvalidEndpoint`, `KeyRejected`,
`AppMismatch`, `NotConfigured`, `ConfigNotLoaded`, `InvalidChoice` e `Unavailable`. Nenhuma
outra exceção sai do SDK: uma falha de disco (cheio, sem permissão) mantém a decisão em
memória, sem derrubar o aplicativo.

## Envio e reenvio

- A decisão vale no aparelho na hora; o registro vai ao servidor numa thread do SDK.
- Sem rede, servidor fora do ar ou limite de chamadas, o registro fica numa fila no aparelho
  e é reenviado com espera crescente, de 5 s até 1 h. Se a chave for recusada por um tempo
  (por exemplo, enquanto a posse do aplicativo é reconferida), a espera começa em 1 h.
- A fila guarda até 50 registros e nenhum com mais de 30 dias.
- O reenvio automático vale enquanto o processo do aplicativo está vivo. Quando o aplicativo
  abre de novo, o `configure` retoma a fila.
- Se o processo morrer entre a confirmação do servidor e a retirada do item da fila, o
  registro é reenviado ao abrir de novo e a decisão fica gravada duas vezes no servidor: uma
  duplicata da mesma decisão, nunca uma decisão inventada.

## Quem desenha a tela responde por ela

No modo sem tela, a tela de consentimento é do aplicativo. A LGPD pede consentimento livre,
informado e inequívoco, e a empresa responde por ele:

- mostre as finalidades e os textos do banner publicado (`config.categories`), sem esconder
  nenhuma;
- recusar precisa ser tão fácil quanto aceitar;
- nada opcional pode ser ligado antes da decisão (`blockBeforeConsent`);
- ofereça um caminho para mudar a decisão depois (`revoke()` ou uma nova tela).

## O que é enviado

Cada decisão leva:
- um identificador aleatório gerado pelo SDK neste aparelho, guardado no armazenamento
  privado do aplicativo e fora do backup;
- a ação, as finalidades e a versão do banner;
- o nome do pacote, a versão do SDK, a versão do Android e o modelo do aparelho.

O endereço IP chega ao servidor como em qualquer chamada e é guardado só como hash. O SDK
**nunca** lê o identificador de publicidade, o `ANDROID_ID` nem outro identificador do
aparelho.

## Desenvolvimento

```bash
./gradlew :consent:testDebugUnitTest :consent:assembleRelease
```

O build de release falha se o AAR passar de 100 KB. As dependências do build são conferidas
contra `gradle/verification-metadata.xml`, e o `gradle-wrapper.jar` é validado no CI antes de
qualquer `./gradlew`. Depois de mudar uma dependência, regenere o arquivo:

```bash
./gradlew --write-verification-metadata sha256 :consent:testDebugUnitTest :consent:assembleRelease
```

### App de teste

O módulo `sample/` é um aplicativo de teste (`io.gettrusto.consent.sample`) que desenha o
banner publicado e chama o SDK nos botões: aceitar tudo, recusar tudo, salvar por
finalidade, revogar e enviar agora, com o estado de `isGranted` e da fila na tela. Ele nunca
é publicado: o lançamento monta só o `:consent`.

A chave e o coletor vêm do `local.properties`, que fica fora do git:

```properties
trusto.sample.key=trusto_pk_test_...
# opcional, o padrão é o coletor de staging
trusto.sample.endpoint=https://...
```

Sem chave, o app usa uma chave falsa no formato certo e o coletor responde 401. Para
exercitar o fluxo inteiro sem chave, suba o coletor falso e toque em "Trocar coletor" (o
emulador o vê em `http://10.0.2.2:8787`):

```bash
python3 -I sample/tools/mock-collector.py
./gradlew :sample:installDebug
```

O Android bloqueia `http` por padrão antes da regra do SDK: o app de teste libera só
`10.0.2.2`, e só no debug (`sample/src/debug/res/xml/network_security_config.xml`). Um app
que queira testar contra um coletor local precisa da mesma liberação.

Para cadastrar o app de teste numa organização, a impressão SHA-256 é a do certificado que
assina o APK de debug na sua máquina:

```bash
keytool -list -v -keystore ~/.android/debug.keystore -storepass android -alias androiddebugkey | grep SHA256
```

## Segurança

Vulnerabilidades: veja [SECURITY.md](SECURITY.md). A publicação sai só pela pipeline de
lançamento, a partir da `main`, com aprovação obrigatória e tags imutáveis.

## Pendências

- **Publicação**: conta no repositório central de pacotes e chave de assinatura, antes do
  primeiro lançamento.

## Licença

[Apache License 2.0](LICENSE).
