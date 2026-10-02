# BabyCam

App Android para usar uma câmera IP (RTSP) como babá eletrônica. O app abre direto no vídeo e no áudio da câmera e nunca para de tentar reconectar. Uma imagem congelada sempre aparece com um aviso de que não é ao vivo.

## Funcionalidades

- **Vídeo e áudio ao vivo via RTSP**, com usuário e senha opcionais.
- **Baixa latência:** buffer de 500 ms para iniciar e no máximo 2 s; RTP sobre TCP.
- **Reconexão automática** com backoff exponencial (1 s → no máximo 10 s), sem desistir nunca.
- **Detecção de travamento:** se nenhum frame novo aparecer em 5 s, o app reconecta na hora. O último frame fica na tela com o banner *"Transmissão travou por 5s — reconectando..."*.
- **Controle de atraso:** se o atraso passar de 3 s por mais de 5 s seguidos, o app reconecta para voltar ao tempo real.
- **Volta da rede:** quando o Wi-Fi volta, o app reconecta sem esperar o backoff.
- **Mute persistente** e **Picture-in-Picture** com botão de mute.
- **Tela sempre ligada** enquanto o vídeo está aberto; tela cheia em paisagem.
- **Configuração da câmera** guardada criptografada (`EncryptedSharedPreferences`).

## Requisitos

- Android 8.0 (API 26) ou superior
- Câmera IP com RTSP que aceite **RTP sobre TCP**
- Celular e câmera na mesma rede (ou câmera acessível pela rede do celular)

## Instalação

Gere o APK de debug e instale:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Sem `adb`: copie `app-debug.apk` para o celular, abra o arquivo e permita "instalar apps de fontes desconhecidas".

## Uso

1. Abra o app e toque em **Adicionar câmera**.
2. Preencha **Host** (IP da câmera), **Porta** (padrão `554`), **Path** (ex.: `/live`, `/stream1`, conforme o fabricante) e, se houver, **Usuário** e **Senha**.
3. Toque em **Salvar**. O vídeo começa sozinho e o app abre direto nele nas próximas vezes.

O app monta a URL `rtsp://usuario:senha@host:porta/path`. Para editar ou remover a câmera, use o ícone de engrenagem.

## Desenvolvimento

Stack: Kotlin, Jetpack Compose, AndroidX Media3 (ExoPlayer + extensão RTSP) 1.4.1, Gradle 8.14 / AGP 8.7.

```bash
./gradlew testDebugUnitTest                    # testes unitários (JUnit + Robolectric)
./gradlew testDebugUnitTest assembleDebug lint # gate completo
```

Estrutura (`app/src/main/java/com/babycam/app/`):

| Pacote | Conteúdo |
| --- | --- |
| `MainActivity.kt` | Única Activity: troca de telas, PiP, ciclo de vida, callback de rede |
| `ui/CameraViewModel.kt` | Máquina de estados (`Connecting` → `Playing` ⇄ `Stalled` / `Reconnecting`), reconexão e monitor de saúde |
| `ui/screens/` | Telas Compose: vazia, formulário e visualização (com banner de travamento) |
| `player/` | `RtspPlayerController` e a implementação Media3 (perfil de baixa latência) |
| `reconnect/` | `BackoffPolicy` e `StreamHealthMonitor` (detecção de travamento e atraso) |
| `data/` | `CameraConfigStore` (persistência criptografada) |

### Diagnóstico

Cada reconexão é registrada com o motivo (`STALL`, `DRIFT`, `ERROR`, `CONNECT_TIMEOUT`, `NETWORK_AVAILABLE`):

```bash
adb logcat -s CameraViewModel Media3RtspPlayerController
```

## Limitações conhecidas

- Uma câmera por vez.
- Funciona só com o app aberto ou em PiP; não há serviço em background nem notificações.
- Câmeras que não aceitam RTP sobre TCP não conectam.
