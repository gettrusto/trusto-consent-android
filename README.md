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
| `configure(context, chave, endpoint)` | Prepara o SDK. Chame uma vez, no `Application`. `endpoint` serve para testar em outro ambiente |
| `loadConfig { }` / `awaitConfig()` | O banner publicado. Cache de 60 s; sem rede, o último salvo |
| `needsConsent` | `true` sem decisão, com banner de outra versão ou com a decisão vencida |
| `acceptAll()`, `rejectAll()`, `save(map)`, `revoke()` | Grava a decisão. Vale no aparelho na hora e é enviada em segundo plano |
| `decision` | A decisão em vigor, com o recibo (`consentId`) quando o envio foi confirmado |
| `isGranted(chave)` | Se a finalidade está concedida. Sem decisão, só as obrigatórias |
| `addListener { }` | Avisado na thread principal a cada decisão, recibo ou banner novo |
| `flush()` | Tenta enviar agora o que estiver pendente |
| `visitorId` | O identificador que o titular informa num pedido de direitos |

Os erros são `ConsentException`: `InvalidKey`, `KeyRejected`, `AppMismatch`,
`NotConfigured`, `ConfigNotLoaded`, `InvalidChoice` e `Unavailable`.

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

O build de release falha se o AAR passar de 100 KB.

## Pendências

- **Licença**: a definir antes da primeira publicação.
- **Publicação**: conta no repositório central de pacotes, chave de assinatura e proteção de
  branch com revisão obrigatória, antes do primeiro lançamento.
