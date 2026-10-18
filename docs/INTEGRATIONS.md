# Integrações: OBS, vMix, NDI, USB, webcam virtual

| Caminho | Status | Observação |
|---|---|---|
| **OBS – Fonte de Mídia** `tcp://127.0.0.1:47810`, formato `h264` | Implementado, **não testado** | Procedimento no README. Inicie o celular antes de abrir a fonte. |
| **vMix** | **Pendente de validação** | O vMix aceita fontes de rede/stream, mas não foi verificado se aceita H.264 Annex-B cru por TCP. Alternativa planejada: o receptor republicar em SRT/RTSP/MPEG-TS (exige FFmpeg embutido) ou NDI. Não declare como suportado até testar. |
| **NDI real** | **Não implementado** | O NDI SDK (https://ndi.video/for-developers/ndi-sdk/download/) exige aceite de licença e tem termos de redistribuição/atribuição (Vizrt NDI AB). Não foi baixado nem redistribuído aqui. Como proceder: baixar o SDK, verificar a licença para sua forma de distribuição, e adicionar um módulo `windows/CameraLive.Ndi` que receba o H.264 do `ReceiverServer`, decodifique e envie como fonte NDI; o suporte NDI *no celular* não é necessário nesta arquitetura. Nenhum protocolo aqui é rotulado como NDI. |
| **Webcam virtual Windows** | **Não implementada** | Exige filtro DirectShow ou Media Foundation Virtual Camera (Windows 11) e decodificação no receptor. Fase seguinte. |
| **USB** | **Não implementado** | Caminho previsto: `adb reverse tcp:47800 tcp:47800` — o app conectaria em `127.0.0.1` e o restante do protocolo permaneceria igual. Exige depuração USB e o `adb` (platform-tools). Só será marcado como funcional após teste real com cabo. |
