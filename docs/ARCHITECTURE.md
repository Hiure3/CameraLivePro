# Arquitetura e decisões

```
Android (Kotlin)                                  Windows (C# / WPF)
CameraX ─ImageAnalysis YUV─▶ MediaCodec H.264 ─▶ Streamer ══TCP+AES-GCM══▶ ReceiverServer ─▶ ObsRelay ─▶ OBS (Fonte de Mídia)
   └─ Preview ─▶ JPEG 2/s ──────────────────────▶ (mesmo canal)             └▶ Prévia/estatísticas
UDP broadcast "CLP?" ◀──────────────────────────────────────────────────── DiscoveryLoop
```

## Decisões
- **Transporte próprio sobre TCP** (CLP v1) em vez de WebRTC: WebRTC exigiria biblioteca nativa pesada nos dois lados e um decodificador no Windows; o TCP próprio é pequeno, auditável e testável. Custo: TCP pode acumular latência em redes ruins — mitigado por descarte até o próximo quadro-chave e bitrate adaptativo.
- **Saída para OBS sem decodificar no receptor**: o receptor repassa o H.264 por TCP local; o OBS (FFmpeg) decodifica. Evita embutir FFmpeg/decodificador no instalador. A **prévia** do receptor usa miniaturas JPEG enviadas pelo celular (baixa taxa, só visualização).
- **CameraX + ImageAnalysis → MediaCodec (buffers)**: caminho simples e portável; copia quadros pela CPU, então **4K/60 pode não ser sustentável** em muitos aparelhos. Melhoria futura: pipeline por `Surface` (zero-cópia). A UI só oferece resolução/FPS que a câmera (stream map + faixas AE) e um codificador H.264 declaram suportar — isso não garante desempenho sustentado.
- **Orientação**: o app é fixo em paisagem; rotações de 180° não são tratadas.
- **Receptor self-contained** (.NET 8 embutido): sem pré-requisitos, instalador maior.
- **Segurança**: ver [PROTOCOL.md](PROTOCOL.md) e [PRIVACY.md](../PRIVACY.md).

## Riscos conhecidos
Código ainda não compilado/testado em hardware; latência do OBS com formato `h264` bruto pode exigir ajuste de buffer; OEMs com encoder H.264 problemático; Wi-Fi com isolamento de clientes.
