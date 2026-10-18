# Testes

Legenda: ✅ executado com resultado real · ⏳ roda no GitHub Actions · ❌ não executado (exige hardware/programa externo).

## Automatizados
| Teste | Onde | Status |
|---|---|---|
| Criptografia do protocolo = implementação de referência Python (PBKDF2, HMAC, AES-GCM) | Java/JVM no ambiente de criação | ✅ idêntico |
| Mesmos vetores em C# (xUnit) e Kotlin (JUnit) | CI | ⏳ |
| Handshake com código certo/errado, round-trip cifrado, adulteração, repetição, quadro gigante | `tests/CameraLive.Protocol.Tests` (CI) | ⏳ |
| Compilação do APK + validação (`aapt2 dump badging`: pacote e versão) | CI | ⏳ |
| Publicação do receptor, geração do instalador | CI | ⏳ |
| Instalação silenciosa → iniciar → porta TCP 47800 aberta → desinstalar | CI (runner Windows) | ⏳ |

## Manuais (você precisa executar; não há resultados inventados)
Marque e anote data/aparelho/versão.

| # | Teste | Como | Resultado |
|---|---|---|---|
| 1 | Instalar APK | Abrir o APK no Android 8+ | ❌ |
| 2 | Detecção de câmeras/resoluções | Menus mostram só opções do aparelho; compare com a câmera nativa | ❌ |
| 3 | Conexão e prévia | Pareie, inicie; prévia atualiza ~2×/s no receptor | ❌ |
| 4 | Vídeo no OBS | Fonte de Mídia `tcp://127.0.0.1:47810`, formato `h264` | ❌ |
| 5 | Alternar câmera | Trocar frontal/traseira com transmissão ativa | ❌ |
| 6 | Mudar resolução/FPS ao vivo | Encoder recria; OBS pode precisar reiniciar a fonte | ❌ |
| 7 | Queda de rede | Desligar Wi-Fi 10 s e religar: deve reconectar sozinho | ❌ |
| 8 | Reabrir o receptor | Fechar/abrir o receptor: o app reconecta; **o código muda** — reconexão exigirá novo código (comportamento atual) | ❌ |
| 9 | Permissão negada | Negar câmera: tela explicativa | ❌ |
| 10 | Sem microfone | Não há áudio nesta versão | n/a |
| 11 | Rede instável | Roteador congestionado/2,4 GHz: bitrate deve cair e "descartados" subir | ❌ |
| 12 | CPU/memória/temperatura | Gerenciador de Tarefas (receptor) e indicador do app; 4K via ImageAnalysis usa CPU | ❌ |
| 13 | vMix | Não validado | ❌ |
| 14 | NDI / USB | Não implementados | n/a |
| 15 | Desempenho 4K@60 | Só aparece se o aparelho declarar suporte; **não garantido** | ❌ |

> Nota sobre o reconexão com receptor reiniciado (teste 8): o app guarda apenas o IP, nunca o código; o usuário redigita o código novo. É uma decisão de segurança.
