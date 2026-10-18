# Camera Live Pro 4K

Transforme um celular Android em câmera para transmissões ao vivo: o app captura e codifica o vídeo (H.264 por hardware) e envia, de forma **pareada e criptografada**, pela rede Wi-Fi para o **receptor Windows**, que mostra a prévia e entrega o vídeo ao **OBS Studio**.

> Projeto com identidade própria. Não usa código, telas, ícones ou marcas de outros aplicativos.

## ⚠️ Estado real da versão 0.1.0 (leia antes de usar)

O código foi **escrito e revisado**, mas ainda **não foi compilado nem testado com aparelhos reais**. O ambiente em que foi criado não tinha .NET, Android SDK nem Gradle, nem celular, OBS ou vMix. Por isso a primeira execução do GitHub Actions pode apontar erros de compilação a corrigir (veja [BUILDING.md](BUILDING.md)). Nada abaixo deve ser tratado como "funciona" até a coluna **Testado** ser preenchida por você.

| Recurso | Código escrito | Compilado | Testado |
|---|---|---|---|
| Protocolo (pareamento por código, AES-256-GCM, anti-repetição) | ✅ C# e Kotlin | ⏳ CI | ✅ vetores criptográficos Python = Java (JVM); testes xUnit/JUnit rodam no CI |
| App Android: câmera frontal/traseira, resoluções/FPS detectados, zoom, exposição, toque para focar, grade | ✅ | ⏳ CI | ❌ precisa de celular |
| Codificação H.264 (MediaCodec) e envio | ✅ | ⏳ CI | ❌ precisa de celular |
| Reconexão automática, bitrate adaptativo, estatísticas | ✅ | ⏳ CI | ❌ |
| Descoberta automática (broadcast UDP) e conexão por IP | ✅ | ⏳ CI | ❌ |
| Receptor Windows: prévia, estatísticas, logs, diagnóstico | ✅ | ⏳ CI | ❌ |
| Saída para OBS (fonte de mídia `tcp://127.0.0.1:47810`) | ✅ | ⏳ CI | ❌ precisa de OBS |
| Instalador `.exe` (instalar/iniciar/desinstalar) | ✅ | ⏳ CI | ⏳ teste automático no CI |
| vMix | ⚠️ mesma saída TCP, **não validada** | — | ❌ |
| **NDI** | ❌ não implementado (SDK com licença própria) | — | — |
| Webcam virtual Windows | ❌ não implementado | — | — |
| **USB** (ADB) | ❌ não implementado — **não aparece como funcional** | — | — |
| Áudio / microfone | ❌ não implementado (por isso o app não pede permissão de microfone) | — | — |
| Alternar lentes traseiras (grande-angular etc.), foco manual, balanço de branco | ❌ pendente | — | — |
| Transmissão em segundo plano (serviço) | ❌ pendente — mantenha o app aberto; a tela fica ligada durante a transmissão | — | — |

Detalhes e motivos em [docs/INTEGRATIONS.md](docs/INTEGRATIONS.md) e [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Como obter os arquivos (APK e instalador)

Você não precisa instalar nada no computador: o **GitHub Actions** compila tudo.

1. Crie uma conta e um repositório em <https://github.com> (botão **New repository**).
2. Extraia `CameraLivePro-GitHub.zip`, abra o repositório e use **Add file → Upload files** para enviar **todo o conteúdo** (inclusive a pasta oculta `.github`; se o navegador não enviar pastas ocultas, arraste a pasta `.github` separadamente ou use o GitHub Desktop).
3. Abra a aba **Actions**. Se pedir, clique em **Enable workflows**. A compilação começa sozinha; para rodar de novo clique em **Build → Run workflow**.
4. Ao terminar (cerca de 10–20 min), abra a execução e role até **Artifacts**. Baixe **release-bundle** (contém `CameraLivePro-Android.apk`, `CameraLivePro-Setup-x64.exe`, `CameraLivePro-Portable.zip`, `CameraLivePro-GitHub.zip`, `BUILD-REPORT.txt` e `SHA256SUMS.txt`).
5. Se algum job ficar vermelho, clique nele para ver o log da etapa que falhou (veja [BUILDING.md](BUILDING.md)).

## Instalação e uso

### 1) Receptor no Windows 10/11 (64 bits)
Execute `CameraLivePro-Setup-x64.exe`. Escolha a pasta, mantenha marcada a opção do **Firewall** (libera apenas redes privadas, portas TCP 47800 e UDP 47801) e conclua. O receptor abre mostrando um **código de 8 dígitos** e o **IP do PC**.

> O Windows SmartScreen pode avisar "editor desconhecido", pois o instalador não é assinado digitalmente. Clique em *Mais informações → Executar assim mesmo*.

### 2) App no Android 8.0+
Copie `CameraLivePro-Android.apk` para o celular, abra-o e permita "instalar apps desconhecidos" para o app de arquivos/navegador usado. É um APK de teste (assinado com chave de depuração).

### 3) Conectar
1. Celular e PC na **mesma rede Wi-Fi** (não use "rede de convidados").
2. No app, permita a câmera, toque **Procurar PC na rede** (ou digite o IP mostrado no receptor) e digite o **código de 8 dígitos**.
3. Escolha câmera, resolução e FPS (só aparecem combinações suportadas pelo aparelho) e toque **Iniciar transmissão**. A prévia aparece no receptor.

### 4) OBS Studio
1. Com a transmissão já iniciada, em **Fontes** clique em **+ → Fonte de Mídia**.
2. Desmarque **Arquivo local**. Em **Entrada** digite `tcp://127.0.0.1:47810` e em **Formato de entrada** digite `h264`.
3. Desmarque **Usar buffer de rede** (menor latência) e clique OK. O receptor mostra "OBS conectado: sim".
4. Se a imagem não aparecer, clique com o botão direito na fonte → **Propriedades → Atualizar cache** (ou reinicie a fonte) com o celular transmitindo.

### vMix
Não validado. Ver [docs/INTEGRATIONS.md](docs/INTEGRATIONS.md).

## Problemas comuns
| Sintoma | O que fazer |
|---|---|
| "Procurar PC" não acha nada | Digite o IP manualmente. Alguns roteadores bloqueiam broadcast. |
| Nem pelo IP conecta | Firewall do Windows (reinstale marcando a opção) ou *isolamento de clientes/AP isolation* no roteador; PC e celular em redes diferentes. Use **Diagnóstico de rede** no receptor. |
| "Código incorreto" | Digite o código mostrado agora no receptor. Após 5 erros há bloqueio de 60 s. |
| Imagem travando | Reduza resolução/FPS/qualidade; use Wi-Fi 5 GHz; veja "Quadros descartados" no receptor. |
| Celular esquentando | O app avisa; reduza resolução/FPS ou encerre. |
| Porta em uso ao abrir o receptor | Há outra cópia aberta. |

## Documentação
[BUILDING.md](BUILDING.md) · [TESTING.md](TESTING.md) · [PRIVACY.md](PRIVACY.md) · [docs/PROTOCOL.md](docs/PROTOCOL.md) · [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [docs/INTEGRATIONS.md](docs/INTEGRATIONS.md) · [LICENSES/THIRD-PARTY.md](LICENSES/THIRD-PARTY.md)
