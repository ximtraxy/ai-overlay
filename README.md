# AI Overlay

**Gemini, Claude e ChatGPT in una bolla sempre a portata di mano, sopra qualsiasi app Android.**
Cerchi con la penna una parte dello schermo e finisce direttamente nella chat.

> 🇬🇧 *A floating overlay for Android tablets that keeps Gemini, Claude and ChatGPT one tap away on top of any app (your notes, a PDF, a lecture). Circle part of the screen with the pen and it lands in the chat. It uses the official web apps with your own account and subscription: no API keys. Android 11+.*

[![Release](https://img.shields.io/github/v/release/ximtraxy/ai-overlay?label=download)](https://github.com/ximtraxy/ai-overlay/releases/latest)
![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

---

## Perché

Studi o lavori sul tablet con l'app degli appunti aperta e vuoi chiedere qualcosa a un'AI senza cambiare app, copiare, incollare e tornare indietro. AI Overlay mette le tre chat in un pannello fluttuante sopra gli appunti, e lo screenshot "cerchia e chiedi" porta il pezzo di pagina che ti interessa direttamente nella conversazione.

## Cosa fa

| | |
|---|---|
| 🫧 **Bolla sempre disponibile** | Una bolla "AI" semitrasparente sopra tutte le app. Si trascina e si aggancia al bordo. |
| 🗂️ **Tre chat in un pannello** | Schede **Gemini · Claude · ChatGPT**. Il pannello si sposta e si ridimensiona, e ricorda posizione e dimensione. Le chat restano aperte (e continuano a rispondere) anche quando lo chiudi. |
| ✂️ **Cerchia e chiedi** | Tieni premuta la bolla (o premi ✂): screenshot, cerchi con la penna la parte che ti serve, e il ritaglio viene allegato alla chat aperta. |
| ⚡ **Domande rapide** | Dopo il cerchio compaiono pulsanti come *Spiega · Risolvi passo passo · Riassumi · Traduci in italiano · Fammi un esempio*. Un tocco e la domanda parte. Sono modificabili. |
| 📋 **Copia come immagine** | Cerchi una parte dello schermo (per esempio la risposta) e la trovi negli appunti di sistema come immagine, pronta da incollare nelle note. Formule e tabelle restano intatte. |
| ✍️ **Pensata per la penna** | Dopo il primo uso della penna, mentre cerchi, dita e palmo vengono ignorati. |
| ⌨️ **Sopra la tastiera** | Quando scrivi, il pannello si accorcia e la casella della chat resta sopra la tastiera, con qualunque tastiera. |
| 🔘 **Interruttore rapido** | Un riquadro nelle Impostazioni rapide nasconde o mostra la bolla con un tocco. |
| 🎤 📥 | Dettatura con il microfono (Claude, ChatGPT) e download di file e immagini generate. |

## Installazione

1. Scarica **`AI-Overlay-x.y.z.apk`** dall'ultima [release](https://github.com/ximtraxy/ai-overlay/releases/latest) e aprilo sul tablet. Se Android lo chiede, consenti l'installazione da questa origine.
2. Apri **AI Overlay** e segui i pulsanti della schermata iniziale:
   1. **Mostra sopra altre app**: serve per il pannello.
   2. **Accessibilità → AI Overlay**: serve per la bolla, lo screenshot e la posizione della tastiera.
   3. **Microfono** (facoltativo): serve per la dettatura nelle chat.
   4. **Interruttore rapido** (facoltativo): lo aggiunge alle Impostazioni rapide.
3. Tocca la bolla e accedi una volta a Gemini, Claude e ChatGPT con il tuo account.

> **L'interruttore in Accessibilità è grigio?** Android blocca questa impostazione per le app installate da file. Vai in *Impostazioni → App → AI Overlay → ⋮ → Consenti impostazioni con restrizioni* e riprova.

## Come si usa

| Gesto | Azione |
|---|---|
| Tocco sulla bolla | Apre o chiude il pannello |
| Pressione lunga sulla bolla, oppure ✂ | Screenshot → cerchi → il ritaglio va nella chat |
| 📋 | Screenshot → cerchi → il ritaglio va negli appunti come immagine |
| Tocco senza cerchiare / *▢ Tutto lo schermo* | Usa l'intero schermo |
| Gesto Indietro mentre cerchi | Annulla |
| `⠿` e `◢` | Spostano e ridimensionano il pannello |

Le domande rapide e l'opzione "solo penna" si cambiano dall'app **AI Overlay**.

## Privacy e permessi

AI Overlay **non ha un server e non raccoglie dati**. Le chat sono i siti ufficiali (`gemini.google.com`, `claude.ai`, `chatgpt.com`) aperti dentro l'app: parli direttamente con Google, Anthropic e OpenAI con il tuo account, come faresti dal browser.

| Permesso | A cosa serve |
|---|---|
| Accessibilità | Mostrare la bolla, fare lo screenshot **solo quando lo chiedi tu**, e conoscere la **posizione** della finestra della tastiera. Android lo descrive come "visualizzare e controllare lo schermo", ma l'app non legge il contenuto dello schermo e non compie azioni al posto tuo. |
| Mostra sopra altre app | Il pannello con le chat. |
| Microfono | Solo per i tre siti di chat, e solo quando premi 🎤. |
| Internet | Caricare le chat. |

Gli screenshot restano nella cache dell'app. Arrivano a una chat solo quando cerchi qualcosa e lo mandi tu.

## Limiti noti

- **Accesso con Google a Gemini**: Google a volte blocca l'accesso dentro le app. Se succede, riprova più tardi o accedi da un altro metodo quando il sito lo permette.
- **Microfono su Gemini**: il sito usa una funzione di Chrome che non è disponibile nelle app. Usa la dettatura della tastiera.
- **I siti cambiano**: l'allegato automatico e l'invio delle domande rapide si basano sulla struttura attuale delle pagine. Se un sito cambia, il ritaglio resta comunque pronto: tocca 📎 / + nella chat per allegarlo.
- **Incolla da 📋**: dipende dall'app di appunti, che deve accettare immagini dagli appunti di sistema.
- Provata soprattutto su un **Lenovo Tab (Android 15)**. Funziona anche su telefono, ma è pensata per tablet.

## Compilare dal sorgente

Servono JDK 17 o superiore e l'Android SDK (platform 35).

```bash
git clone https://github.com/ximtraxy/ai-overlay.git
cd ai-overlay
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # test
```

Per una build firmata, crea un `keystore.properties` nella cartella principale (è già nel `.gitignore`):

```properties
storeFile=/percorso/della/tua-chiave.jks
storePassword=...
keyAlias=...
keyPassword=...
```

e lancia `./gradlew assembleRelease`.

### Struttura

```
app/src/main/java/dev/aioverlay/
├── OverlayService.kt   bolla, pannello, WebView, screenshot, domande rapide, tastiera
├── MainActivity.kt     setup dei permessi e impostazioni
├── PickActivity.kt     selettore file per il tasto 📎 delle chat
├── ShotProvider.kt     condivide i ritagli 📋 con le altre app
└── ToggleTile.kt       interruttore nelle Impostazioni rapide
```

Kotlin, nessuna dipendenza esterna: solo le API di Android.

## Avviso

Progetto indipendente e non ufficiale. Non è affiliato né approvato da Google, Anthropic o OpenAI. Gemini, Claude e ChatGPT sono marchi dei rispettivi proprietari. Per usare i servizi valgono i loro termini d'uso.

## Licenza

[MIT](LICENSE)
