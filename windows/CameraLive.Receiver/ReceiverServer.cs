using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using CameraLive.Protocol;

namespace CameraLive.Receiver;

public sealed record PhoneStats(double Fps, int RttMs, int BitrateKbps, int Battery, double TempC, long Dropped);

public sealed class SessionSnapshot
{
    public string Phone { get; init; } = "";
    public StreamConfig? Config { get; init; }
    public PhoneStats? Stats { get; init; }
    public double RecvFps { get; init; }
    public double RecvKbps { get; init; }
}

public sealed class ReceiverServer : IDisposable
{
    static readonly JsonSerializerOptions J = new() { PropertyNameCaseInsensitive = true };
    readonly TcpListener _listener = new(IPAddress.Any, Ports.Stream);
    readonly CancellationTokenSource _cts = new();
    readonly ObsRelay _relay;
    int _failures; DateTime _lockedUntil = DateTime.MinValue;
    volatile bool _busy;

    public string Code { get; private set; } = Pairing.NewCode();
    public event Action<string>? StatusChanged;
    public event Action<byte[]>? PreviewReceived;
    public event Action<SessionSnapshot?>? SessionUpdated;

    public ReceiverServer(ObsRelay relay) => _relay = relay;

    public void NewCode() { Code = Pairing.NewCode(); Log.Write("Novo código de pareamento gerado."); }

    public void Start()
    {
        _listener.Start();
        _ = Task.Run(AcceptLoop);
        _ = Task.Run(DiscoveryLoop);
        StatusChanged?.Invoke("Aguardando o celular…");
        Log.Write($"Receptor ouvindo na porta TCP {Ports.Stream}");
    }

    async Task AcceptLoop()
    {
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                var tcp = await _listener.AcceptTcpClientAsync(_cts.Token);
                if (_busy) { tcp.Dispose(); Log.Write("Conexão recusada: já existe um celular transmitindo."); continue; }
                if (DateTime.UtcNow < _lockedUntil) { tcp.Dispose(); Log.Write("Conexão recusada: bloqueio temporário por tentativas erradas."); continue; }
                _busy = true;
                _ = Task.Run(() => HandleClient(tcp));
            }
            catch (OperationCanceledException) { return; }
            catch (Exception ex) { Log.Write("Aceitar conexão: " + ex.Message); }
        }
    }

    async Task HandleClient(TcpClient tcp)
    {
        var remote = tcp.Client.RemoteEndPoint?.ToString() ?? "?";
        try
        {
            tcp.NoDelay = true;
            using (tcp)
            {
                var stream = tcp.GetStream();
                SecureChannel ch; PeerInfo peer;
                using (var hs = CancellationTokenSource.CreateLinkedTokenSource(_cts.Token))
                {
                    hs.CancelAfter(TimeSpan.FromSeconds(10));
                    try { (ch, peer) = await Handshake.ServerAsync(stream, Code, hs.Token); }
                    catch (ProtocolException ex)
                    {
                        if (++_failures >= 5) { _lockedUntil = DateTime.UtcNow.AddSeconds(60); _failures = 0; Log.Write("5 falhas de pareamento: bloqueado por 60 s."); }
                        Log.Write($"Pareamento falhou ({remote}): {ex.Message}");
                        StatusChanged?.Invoke("Pareamento recusado. Confira o código no celular.");
                        await Task.Delay(1000);                          // atrasa tentativas de adivinhação
                        return;
                    }
                }
                _failures = 0;
                Log.Write($"Celular '{peer.Name}' pareado ({remote}).");
                StatusChanged?.Invoke($"Conectado: {peer.Name}");
                await RunSession(ch, peer);
            }
        }
        catch (Exception ex) when (ex is not OperationCanceledException) { Log.Write($"Sessão encerrada: {ex.Message}"); }
        finally
        {
            _busy = false;
            SessionUpdated?.Invoke(null);
            StatusChanged?.Invoke("Aguardando o celular…");
        }
    }

    async Task RunSession(SecureChannel ch, PeerInfo peer)
    {
        StreamConfig? cfg = null; PhoneStats? stats = null;
        long frames = 0, bytes = 0; var sw = Stopwatch.StartNew(); double fps = 0, kbps = 0;
        var ct = _cts.Token;
        while (!ct.IsCancellationRequested)
        {
            // Sem dados por 10 s = conexão considerada perdida (o celular reconecta sozinho).
            using var idle = CancellationTokenSource.CreateLinkedTokenSource(ct);
            idle.CancelAfter(TimeSpan.FromSeconds(10));
            (byte type, byte[] payload) msg;
            try { msg = await ch.ReadAsync(idle.Token); }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested) { throw new IOException("Sem dados do celular por 10 s."); }

            switch (msg.type)
            {
                case MsgType.Config: cfg = JsonSerializer.Deserialize<StreamConfig>(msg.payload, J); Log.Write($"Configuração: {cfg?.Width}x{cfg?.Height} @ {cfg?.Fps} fps, {cfg?.BitrateKbps} kbps"); StatusChanged?.Invoke($"Transmitindo: {peer.Name}"); break;
                case MsgType.Video:
                    var v = VideoPacket.Parse(msg.payload);
                    _relay.Publish(v.KeyFrame, v.Data);
                    frames++; bytes += msg.payload.Length; break;
                case MsgType.Preview: PreviewReceived?.Invoke(msg.payload); break;
                case MsgType.Stats: stats = JsonSerializer.Deserialize<PhoneStats>(msg.payload, J); break;
                case MsgType.Ping: await ch.WriteAsync(MsgType.Pong, msg.payload, ct); break;
                default: Log.Write($"Mensagem desconhecida ignorada (tipo {msg.type})."); break;
            }
            if (sw.ElapsedMilliseconds >= 1000)
            {
                fps = frames * 1000.0 / sw.ElapsedMilliseconds; kbps = bytes * 8.0 / sw.ElapsedMilliseconds;
                frames = 0; bytes = 0; sw.Restart();
                SessionUpdated?.Invoke(new SessionSnapshot { Phone = peer.Name, Config = cfg, Stats = stats, RecvFps = fps, RecvKbps = kbps });
            }
        }
    }

    /// <summary>Responde a "CLP?" (broadcast UDP) com "CLP1|nome|porta".</summary>
    async Task DiscoveryLoop()
    {
        try
        {
            using var udp = new UdpClient(new IPEndPoint(IPAddress.Any, Ports.Discovery));
            while (!_cts.IsCancellationRequested)
            {
                var r = await udp.ReceiveAsync(_cts.Token);
                if (r.Buffer.Length == 4 && Encoding.ASCII.GetString(r.Buffer) == "CLP?")
                {
                    var reply = Encoding.UTF8.GetBytes($"CLP1|{Environment.MachineName}|{Ports.Stream}");
                    await udp.SendAsync(reply, r.RemoteEndPoint, _cts.Token);
                }
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) { Log.Write("Descoberta indisponível: " + ex.Message + " (conecte pelo IP)."); }
    }

    public void Dispose() { _cts.Cancel(); try { _listener.Stop(); } catch { } }
}
