# Dependências de terceiros

Nenhum código de terceiros é copiado para o repositório; tudo é baixado na compilação.

| Componente | Uso | Licença |
|---|---|---|
| AndroidX (CameraX, Compose, Lifecycle, Core, Activity) | App Android | Apache-2.0 |
| Kotlin / kotlinx.coroutines | App Android | Apache-2.0 |
| .NET 8 / WPF (runtime incluído no publish self-contained) | Receptor Windows | MIT |
| xUnit / Microsoft.NET.Test.Sdk | Testes | Apache-2.0 / MIT |
| Inno Setup | Instalador (somente na compilação) | Licença própria, gratuita — https://jrsoftware.org/files/is/license.txt |
| Python `cryptography` | Somente script de vetores de teste | Apache-2.0 / BSD |

**NDI**: o NDI SDK **não** está incluído. "NDI" é marca registrada da Vizrt NDI AB. Ver docs/INTEGRATIONS.md.
**OBS Studio** (GPLv2) e **vMix** são programas externos; este projeto não os redistribui.
