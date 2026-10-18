# Compilando o projeto

## Pelo GitHub Actions (recomendado)
Veja o passo a passo no [README](README.md#como-obter-os-arquivos-apk-e-instalador).

- **Actions**: aba superior do repositório. Cada execução lista os jobs: *Testes do protocolo (C#)*, *APK Android*, *Receptor + instalador Windows* e *Relatório, hashes e ZIP*.
- **Resultado de cada etapa**: clique no job; etapas com ✅/❌. Clique numa etapa para ver o log; copie as linhas de `error` ao pedir ajuda.
- **Artefatos**: fim da página da execução → **Artifacts** (`android-apk`, `windows-installer`, `release-bundle`, resultados de testes).
- **Nova compilação**: Actions → **Build** → **Run workflow**.
- **Release**: Actions → **Release (rascunho)** → Run workflow → informe a tag (ex.: `v0.1.0`). Cria um *rascunho* em **Releases**; revise e publique manualmente. Nada é publicado automaticamente.

Primeira execução: como o código ainda não foi compilado por quem o escreveu, é esperado precisar corrigir erros de compilação. Etapas independentes (protocolo, Android, Windows) falham separadamente.

## Localmente
Requisitos: **JDK 17**, **Android Studio** (SDK 34) ou apenas Gradle 8.7 + Android SDK, **.NET SDK 8**, Windows para o receptor/instalador, **Inno Setup 6** para o instalador. Dependências são baixadas automaticamente (Maven Google/Central, NuGet).

```bash
# Testes do protocolo (qualquer SO)
dotnet test tests/CameraLive.Protocol.Tests
# APK (debug) — com Gradle 8.7 instalado, ou abra a pasta android/ no Android Studio
gradle -p android testDebugUnitTest assembleDebug
#   saída: android/app/build/outputs/apk/debug/app-debug.apk
# Receptor Windows
dotnet publish windows/CameraLive.Receiver -c Release -r win-x64 --self-contained true -o publish/receiver
# Instalador (Windows, Inno Setup instalado)
ISCC installer\CameraLivePro.iss        # saída: dist\CameraLivePro-Setup-x64.exe
# ZIP do projeto
bash scripts/make-zip.sh CameraLivePro-GitHub.zip
```
Android Studio: **File → Open →** pasta `android/`.

## APK de release assinado (opcional)
O APK do CI é de *debug*. Para release, crie uma keystore, guarde-a em **Settings → Secrets and variables → Actions** (por exemplo `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`) e adicione a etapa de assinatura ao workflow. **Nunca** coloque keystore ou senhas no repositório.

## Regerar vetores de teste do protocolo
`python3 scripts/protocol_vectors.py` (requer `pip install cryptography`). Os mesmos valores estão nos testes C# e Kotlin.
