# Política de privacidade (v0.1.0)

- **Coleta**: o projeto não coleta, envia ou armazena dados pessoais em servidores. Não há anúncios, telemetria nem contas.
- **Câmera**: usada somente após permissão do Android e somente enviada quando você toca em *Iniciar transmissão*. Um indicador "AO VIVO" fica visível, e a tela permanece ligada durante a transmissão.
- **Microfone**: não utilizado nesta versão (permissão não solicitada).
- **Rede**: o vídeo vai **diretamente** do celular ao PC pela rede local, cifrado com AES-256-GCM, após pareamento por código de 8 dígitos. Nada passa pela internet.
- **Armazenamento no celular**: apenas o último IP digitado (SharedPreferences). O código **não** é salvo.
- **Armazenamento no PC**: log em `%LOCALAPPDATA%\CameraLivePro\logs\receiver.log` (eventos de conexão, IPs de origem, erros). O código de pareamento e o vídeo nunca são gravados.
- **Saída para OBS**: o relé escuta somente em `127.0.0.1` (este PC). O vídeo repassado ao OBS **não** é cifrado nesse trecho local.
- **Portas**: TCP 47800 (celular→PC), UDP 47801 (descoberta; responde apenas "CLP1|nome-do-PC|porta"), TCP 47810 local. Libere apenas o programa do receptor, em redes privadas.
- **Limite de segurança conhecido**: o código tem 8 dígitos. Contra quem *captura* o tráfego do handshake na sua rede, é possível tentar adivinhá-lo offline. Use apenas em redes confiáveis; o receptor limita tentativas online (5 erros → 60 s).
